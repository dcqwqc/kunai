package dev.kitsunekode.kunai.android

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.kitsunekode.kunai.android.data.Library
import dev.kitsunekode.kunai.android.data.Stream
import dev.kitsunekode.kunai.android.data.Subtitle
import dev.kitsunekode.kunai.android.data.Title
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * On-device Media3 player with Kunai-owned, fixed-size Ember Dusk controls.
 * No WebView, generic mpv/Exo controls, desktop session or relay is involved.
 * Both Android TV remote keys and phone touch use the same play/seek/CC state.
 */
class PlayerActivity : Activity() {
    private val bg = Color.rgb(16, 11, 15)
    private val panel = Color.rgb(33, 25, 36)
    private val pink = Color.rgb(255, 143, 176)
    private val text = Color.rgb(246, 239, 244)
    private val muted = Color.rgb(171, 157, 172)
    private val border = Color.rgb(73, 57, 75)

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var playerView: PlayerView? = null
    private var title: Title? = null
    private var season = 1
    private var episode = 1
    private lateinit var library: Library
    private var root: FrameLayout? = null
    private var controls: ViewGroup? = null
    private var playControl: TextView? = null
    private var timeText: TextView? = null
    private var timeSeek: SeekBar? = null
    private var bufferText: TextView? = null
    private var errorPanel: LinearLayout? = null
    private var captions: TextView? = null
    private var controlsVisible = true
    private var scrubbing = false
    private var captionsEnabled = true
    private var lastTouch = 0L
    private val clock = Handler(Looper.getMainLooper())
    private val update = object : Runnable {
        override fun run() {
            refreshControls()
            if (controlsVisible && SystemClock.uptimeMillis() - lastTouch > 5500 &&
                !scrubbing && player?.isPlaying == true && errorPanel?.visibility != View.VISIBLE
            ) showControls(false)
            clock.postDelayed(this, 350)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = Library(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val raw = intent.getStringExtra("stream") ?: return failureScreen("No playable source")
        val parsedTitle = intent.getStringExtra("title")?.let {
            runCatching { Library.decode(JSONObject(it)) }.getOrNull()
        } ?: return failureScreen("Missing title")
        title = parsedTitle
        season = intent.getIntExtra("season", 1).coerceAtLeast(1)
        episode = intent.getIntExtra("episode", 1).coerceAtLeast(1)
        val source = runCatching { JSONObject(raw) }.getOrNull()
            ?: return failureScreen("Invalid stream")
        val url = source.optString("url")
        if (!validHttps(url)) return failureScreen("The media source is not secure")

        val videoHeaders = linkedMapOf<String, String>()
        source.optJSONObject("headers")?.let { headerJson ->
            headerJson.keys().forEach { key ->
                val value = headerJson.optString(key)
                if (key.lowercase() in setOf("referer", "origin", "user-agent")
                    && value.length <= 350 && !value.contains("\r") && !value.contains("\n"))
                    videoHeaders[key] = value
            }
        }
        val factory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(false)
            .setConnectTimeoutMs(10_000).setReadTimeoutMs(15_000)
            .setDefaultRequestProperties(videoHeaders)
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .build()
        exo.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        exo.setHandleAudioBecomingNoisy(true)
        player = exo
        session = MediaSession.Builder(this, exo).build()

        val label = if (parsedTitle.kind.key == "movie") "Movie"
            else "Season $season · Episode $episode"
        val item = MediaItem.Builder().setUri(url)
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(parsedTitle.name).setSubtitle(label).build())
        val lower = url.substringBefore("?").lowercase()
        if (lower.endsWith(".m3u8") || url.contains("type=hls", ignoreCase = true))
            item.setMimeType(MimeTypes.APPLICATION_M3U8)
        if (lower.endsWith(".mpd")) item.setMimeType(MimeTypes.APPLICATION_MPD)
        val subs = source.optJSONArray("subtitles") ?: JSONArray()
        val tracks = buildList {
            for (i in 0 until subs.length()) {
                val row = subs.optJSONObject(i) ?: continue
                val sub = row.optString("uri")
                if (!validHttps(sub)) continue
                val ext = sub.substringBefore("?").lowercase()
                val mime = when {
                    ext.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
                    ext.endsWith(".ass") || ext.endsWith(".ssa") -> MimeTypes.TEXT_SSA
                    ext.endsWith(".ttml") || ext.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
                    else -> MimeTypes.TEXT_VTT
                }
                val lang = row.optString("language", "en").take(15)
                add(MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub))
                    .setMimeType(mime).setLanguage(lang)
                    .setLabel(row.optString("label", lang).take(50)).build())
            }
        }
        if (tracks.isNotEmpty()) item.setSubtitleConfigurations(tracks)

        val video = PlayerView(this).apply {
            player = exo
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            setBackgroundColor(Color.BLACK)
            isFocusable = false
            setOnClickListener { reveal() }
        }
        playerView = video
        val container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root = container
        container.addView(video, FrameLayout.LayoutParams(-1, -1))
        buildControls(container, parsedTitle.name, label)
        setContentView(container)

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    saveProgress(0)
                    showControls(true)
                }
                bufferText?.visibility =
                    if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                refreshControls()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { refreshControls() }
            override fun onPlayerError(error: PlaybackException) {
                showError("This stream could not be played.")
            }
        })
        exo.setMediaItem(item.build())
        exo.prepare()
        val previous = library.position(parsedTitle, season, episode)
        if (previous > 0) exo.seekTo(previous)
        exo.playWhenReady = true
        lastTouch = SystemClock.uptimeMillis()
        clock.post(update)
        playControl?.requestFocus()
    }

    private fun drawable(fill: Int, selected: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(10).toFloat()
            setStroke(if (selected) dp(3) else dp(1), if (selected) pink else border)
        }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float = 13f, color: Int = text): TextView =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
    private fun action(value: String, primary: Boolean = false, width: Int = 46): TextView =
        TextView(this).apply {
            text = value
            textSize = if (primary) 19f else 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (primary) bg else this@PlayerActivity.text)
            background = drawable(if (primary) pink else panel)
            minimumWidth = dp(width)
            minimumHeight = dp(42)
            isClickable = true
            isFocusable = true
            setOnFocusChangeListener { _, focused ->
                background = drawable(if (primary) pink else panel, focused)
            }
            setOnClickListener { lastTouch = SystemClock.uptimeMillis() }
        }

    private fun buildControls(container: FrameLayout, name: String, description: String) {
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(224, 16, 11, 15), Color.TRANSPARENT))
        }
        val back = action("‹", width = 46).apply { setOnClickListener { finish() } }
        toolbar.addView(back, LinearLayout.LayoutParams(dp(46), dp(42)))
        val titleWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, 0, 0)
        }
        titleWrap.addView(label(name, 16f).apply { typeface = Typeface.DEFAULT_BOLD })
        titleWrap.addView(label(description, 11f, muted))
        toolbar.addView(titleWrap, LinearLayout.LayoutParams(0, -2, 1f))
        container.addView(toolbar, FrameLayout.LayoutParams(
            -1, -2, Gravity.TOP))
        controls = toolbar

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(24), dp(14), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.argb(236, 16, 11, 15)))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back10 = action("↶10", width = 51)
        back10.setOnClickListener {
            player?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) }
            reveal()
        }
        row.addView(back10, LinearLayout.LayoutParams(dp(53), dp(43)))
        row.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        val play = action("Ⅱ", primary = true, width = 47)
        play.setOnClickListener {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
            reveal()
        }
        playControl = play
        row.addView(play, LinearLayout.LayoutParams(dp(48), dp(46)))
        row.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        val ahead = action("10↷", width = 51)
        ahead.setOnClickListener {
            player?.let { it.seekTo((it.currentPosition + 10_000)
                .coerceAtMost(it.duration.takeIf { d -> d > 0 } ?: Long.MAX_VALUE)) }
            reveal()
        }
        row.addView(ahead, LinearLayout.LayoutParams(dp(53), dp(43)))
        val timeline = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(pink)
            thumbTintList = ColorStateList.valueOf(pink)
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(114, 94, 111))
            isFocusable = true
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, user: Boolean) {
                    if (user) {
                        val d = player?.duration ?: 0L
                        if (d > 0) timeText?.text = "${format(d * progress / 1000)} / ${format(d)}"
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {
                    scrubbing = true; reveal()
                }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    val exo = player
                    if (exo != null && exo.duration > 0)
                        exo.seekTo(exo.duration * (bar?.progress ?: 0) / 1000)
                    scrubbing = false; reveal()
                }
            })
        }
        timeSeek = timeline
        row.addView(timeline, LinearLayout.LayoutParams(0, dp(45), 1f))
        val timer = label("0:00 / 0:00", 11f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(5), 0, dp(7), 0)
        }
        timeText = timer
        row.addView(timer, LinearLayout.LayoutParams(dp(102), dp(40)))
        val cc = action("CC", width = 44).apply {
            setOnClickListener {
                captionsEnabled = !captionsEnabled
                player?.let { exo ->
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !captionsEnabled).build()
                }
                setTextColor(if (captionsEnabled) pink else muted)
                reveal()
            }
            setTextColor(pink)
        }
        captions = cc
        row.addView(cc, LinearLayout.LayoutParams(dp(43), dp(42)))
        bottom.addView(row, LinearLayout.LayoutParams(-1, -2))
        container.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        // Keep the top and bottom controls in one overlay, without
        // changing their sizes or stealing the video's hardware surface.
        container.removeView(toolbar)
        container.removeView(bottom)
        val overlay = FrameLayout(this)
        overlay.addView(toolbar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        overlay.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        controls = overlay
        container.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        val loading = label("Loading video…", 14f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = drawable(Color.argb(215, 33, 25, 36))
            visibility = View.VISIBLE
        }
        bufferText = loading
        container.addView(loading, FrameLayout.LayoutParams(
            -2, -2, Gravity.CENTER))

        val failure = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(20), dp(26), dp(20))
            background = drawable(Color.argb(245, 33, 25, 36))
            visibility = View.GONE
        }
        failure.addView(label("Stream unavailable", 18f).apply {
            typeface = Typeface.DEFAULT_BOLD;gravity = Gravity.CENTER
        })
        failure.addView(label("This source could not be played.", 13f, muted).apply {
            gravity = Gravity.CENTER;setPadding(0, dp(9), 0, dp(16))
        })
        val retry = action("Retry", primary = true, width = 92).apply {
            setOnClickListener {
                failure.visibility = View.GONE
                player?.prepare();player?.play()
                reveal()
            }
        }
        failure.addView(retry, LinearLayout.LayoutParams(dp(104), dp(45)))
        errorPanel = failure
        container.addView(failure, FrameLayout.LayoutParams(
            -2, -2, Gravity.CENTER))
    }

    private fun format(ms: Long): String {
        if (ms < 0) return "0:00"
        val seconds = (ms / 1000).coerceAtLeast(0)
        val m = seconds / 60
        val s = seconds % 60
        return String.format(Locale.US, "%d:%02d", m, s)
    }
    private fun refreshControls() {
        val exo = player ?: return
        playControl?.text = if (exo.isPlaying) "Ⅱ" else "▶"
        if (!scrubbing && exo.duration > 0 && exo.duration != C.TIME_UNSET) {
            timeSeek?.progress = (1000 * exo.currentPosition / exo.duration)
                .toInt().coerceIn(0, 1000)
        }
        timeText?.text = "${format(exo.currentPosition)} / ${format(exo.duration)}"
    }
    private fun showControls(show: Boolean) {
        controlsVisible = show
        controls?.visibility = if (show) View.VISIBLE else View.GONE
    }
    private fun reveal() {
        lastTouch = SystemClock.uptimeMillis()
        showControls(true)
    }
    private fun showError(message: String) {
        errorPanel?.visibility = View.VISIBLE
        reveal()
    }
    private fun failureScreen(message: String) {
        setContentView(label(message, 16f).apply {
            setBackgroundColor(bg)
            setPadding(dp(24), dp(30), dp(24), dp(30))
            setOnClickListener { finish() }
        })
    }
    private fun validHttps(value: String): Boolean {
        val uri = Uri.parse(value)
        val host = uri.host?.lowercase().orEmpty()
        return uri.scheme == "https" && host.isNotEmpty() && uri.userInfo == null &&
            uri.port in listOf(-1, 443) &&
            host != "localhost" && host != "0.0.0.0" && !host.startsWith("127.") &&
            !host.startsWith("192.168.") && !host.startsWith("10.") &&
            !host.startsWith("169.254.") && !host.endsWith(".local")
    }
    private fun saveProgress(forced: Long? = null) {
        val exo = player ?: return
        val currentTitle = title ?: return
        val duration = exo.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0
        val position = forced ?: exo.currentPosition.coerceAtLeast(0)
        if (duration > 0) library.record(currentTitle, season, episode, position, duration)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE)) {
            player?.let { if (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) it.pause()
                else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) it.play()
                else if (it.isPlaying) it.pause() else it.play() }
            reveal()
            return true
        }
        if (!controlsVisible && keyCode in listOf(
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
            reveal()
            playControl?.requestFocus()
            return true
        }
        if (!controlsVisible && keyCode in listOf(
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
            player?.let {
                val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -10_000L else 10_000L
                it.seekTo((it.currentPosition + delta).coerceAtLeast(0)
                    .coerceAtMost(it.duration.takeIf { d -> d > 0 } ?: Long.MAX_VALUE))
            }
            reveal()
            return true
        }
        lastTouch = SystemClock.uptimeMillis()
        return super.onKeyDown(keyCode, event)
    }
    override fun onStop() {
        saveProgress()
        player?.pause()
        super.onStop()
    }
    override fun onDestroy() {
        clock.removeCallbacks(update)
        saveProgress()
        playerView?.player = null
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }
    companion object {
        fun launch(host: Activity, title: Title, episode: Int, season: Int, stream: Stream) {
            val data = JSONObject().put("url", stream.url).put("headers", JSONObject(stream.headers))
            val tracks = JSONArray()
            stream.subtitles.forEach {
                tracks.put(JSONObject().put("uri", it.uri)
                    .put("language", it.language).put("label", it.label))
            }
            data.put("subtitles", tracks)
            host.startActivity(Intent(host, PlayerActivity::class.java)
                .putExtra("stream", data.toString())
                .putExtra("title", Library.encode(title).toString())
                .putExtra("season", season).putExtra("episode", episode))
        }
    }
}
