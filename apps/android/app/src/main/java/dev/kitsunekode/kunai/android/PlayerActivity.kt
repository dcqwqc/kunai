package dev.kitsunekode.kunai.android

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.util.TypedValue
import kotlin.math.roundToInt
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.util.Log
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
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.text.Cue
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.CaptionStyleCompat
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
    private var durationText: TextView? = null
    private var timeSeek: SeekBar? = null
    private var bufferText: TextView? = null
    private var errorPanel: LinearLayout? = null
    private var captions: TextView? = null
    private var subtitleOverlay: SubtitleView? = null
    private var subtitleVertical: Float = 0.44f
    private var controlsVisible = true
    private var scrubbing = false
    private var captionsEnabled = true
    private var lastTouch = 0L
    private val recovery = PlaybackRecoveryPolicy()
    private var foreground = true
    @Volatile private var lastVideoFrameAtMs = 0L
    private var acknowledgedFrameAtMs = 0L
    private var lastCheckpointAtMs = 0L
    private val clock = Handler(Looper.getMainLooper())
    private val update = object : Runnable {
        override fun run() {
            refreshControls()
            val now = SystemClock.uptimeMillis()
            if (lastVideoFrameAtMs > acknowledgedFrameAtMs) {
                acknowledgedFrameAtMs = lastVideoFrameAtMs
                recovery.framePresented(lastVideoFrameAtMs)
            }
            player?.let { exo ->
                if (exo.isPlaying && exo.playbackState == Player.STATE_READY &&
                    exo.currentPosition >= 1_000L &&
                    (lastCheckpointAtMs == 0L || now - lastCheckpointAtMs >= 3_000L)) {
                    lastCheckpointAtMs = now
                    saveProgress()
                }
                if (recovery.check(now, foreground, exo.playWhenReady &&
                    exo.playbackState != Player.STATE_ENDED,
                    exo.playbackState == Player.STATE_BUFFERING,
                    exo.playbackState == Player.STATE_READY)) {
                    recoverPlayer()
                }
            }
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
        // English is a deliberate default for Japanese audio. Media3 otherwise
        // may leave valid external SRT tracks unselected on Android TV.
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage("en")
            .setSelectUndeterminedTextLanguage(true)
            .build()
        player = exo
        // Decoder-frame timestamps catch freezes even when the playback clock
        // and audio continue advancing. The callback may run off the UI thread.
        exo.setVideoFrameMetadataListener(VideoFrameMetadataListener { _, _, _, _ ->
            lastVideoFrameAtMs = SystemClock.uptimeMillis()
        })
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
                val mime = row.optString("mimeType").takeIf {
                    it in setOf(MimeTypes.TEXT_VTT, MimeTypes.APPLICATION_SUBRIP,
                        MimeTypes.TEXT_SSA, MimeTypes.APPLICATION_TTML)
                } ?: when {
                    ext.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
                    ext.endsWith(".ass") || ext.endsWith(".ssa") -> MimeTypes.TEXT_SSA
                    ext.endsWith(".ttml") || ext.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
                    else -> MimeTypes.TEXT_VTT
                }
                val lang = row.optString("language", "en").take(15)
                add(MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub))
                    .setMimeType(mime).setLanguage(lang)
                    .setSelectionFlags(if (lang.equals("en", true) ||
                        lang.startsWith("en-", true)) C.SELECTION_FLAG_DEFAULT else 0)
                    .setLabel(row.optString("label", lang).take(50)).build())
            }
        }
        if (tracks.isNotEmpty()) item.setSubtitleConfigurations(tracks)
        Log.i("KunaiCaptions", "Provided subtitle tracks: " +
            tracks.map { it.language ?: "und" })

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
        // The original Media3 subtitle widget can disappear behind hardware
        // video surfaces on XGIMI. Render the same decoded cues in our own
        // foreground overlay, with the user's old Kunai subtitle styling.
        video.subtitleView?.visibility = View.GONE
        val container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root = container
        container.addView(video, FrameLayout.LayoutParams(-1, -1))
        val appearance = runCatching {
            JSONObject(assets.open("kunai-tv-subtitles.json").bufferedReader().use { it.readText() })
        }.getOrDefault(JSONObject())
        subtitleVertical = (appearance.optDouble("position", 44.0)
            .toFloat().coerceIn(10f, 90f) / 100f)
        val font = runCatching { Typeface.createFromAsset(assets, "RobotoCondensed-Bold.ttf") }
            .getOrDefault(Typeface.create("sans-serif", Typeface.BOLD))
        // White RGB; the video colour bleeds subtly through the glyphs.
        val letterOpacity = appearance.optDouble("opacity", 0.88).coerceIn(0.1, 1.0)
        val foreground = Color.argb((letterOpacity * 255).roundToInt(), 255, 255, 255)
        val outlineOpacity = appearance.optDouble("outlineOpacity", 0.40)
            .coerceIn(0.0, 1.0)
        val outlineColor = Color.argb((outlineOpacity * 255).roundToInt(), 0, 0, 0)
        Log.i("KunaiCaptions", "Style RGB=FFFFFF glyphAlpha=" +
            Color.alpha(foreground) + " outlineAlpha=" + Color.alpha(outlineColor) +
            " noBackground=true font=RobotoCondensed-Bold")
        val overlay = SubtitleView(this).apply {
            setApplyEmbeddedStyles(false)
            setApplyEmbeddedFontSizes(false)
            setFixedTextSize(TypedValue.COMPLEX_UNIT_PX,
                appearance.optDouble("fontSize", 35.0).toFloat().coerceIn(18f, 92f))
            setStyle(CaptionStyleCompat(foreground, Color.TRANSPARENT,
                Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                outlineColor, font))
            setCues(emptyList())
            isClickable = false
            isFocusable = false
            contentDescription = "Kunai subtitles"
        }
        subtitleOverlay = overlay
        container.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        buildControls(container, parsedTitle.name, label)
        setContentView(container)

        exo.addListener(object : Player.Listener {
            override fun onTracksChanged(groups: Tracks) {
                val captions = groups.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                    .flatMap { group -> (0 until group.length).map { i ->
                        val format = group.getTrackFormat(i)
                        (format.language ?: "und") + ":" + group.isTrackSelected(i)
                    } }
                Log.i("KunaiCaptions", "Media3 available/selected: $captions")
            }
            override fun onCues(cueGroup: CueGroup) {
                val captions = cueGroup.cues.mapNotNull { it.text?.toString()?.trim() }
                    .filter { it.isNotEmpty() }
                val output = if (captionsEnabled && captions.isNotEmpty()) {
                    val merged = captions.joinToString("\n")
                    // A stable center-aligned layout ignores incorrect source
                    // WebVTT line positions and avoids overlapping cue boxes.
                    listOf(Cue.Builder().setText(merged)
                        .setTextAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setPosition(0.5f)
                        .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                        .setLine(subtitleVertical, Cue.LINE_TYPE_FRACTION)
                        .setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                        .setSize(0.92f).build())
                } else emptyList()
                subtitleOverlay?.setCues(output)
                if (output.isNotEmpty()) {
                    Log.i("KunaiCaptions", "Subtitle cues displayed: " + output.size)
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    saveProgress(0, durable = true)
                    showControls(true)
                }
                bufferText?.visibility =
                    if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                refreshControls()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying && exo.playbackState == Player.STATE_READY &&
                    exo.currentPosition > 0) saveProgress(durable = true)
                refreshControls()
            }
            override fun onPlayerError(error: PlaybackException) {
                if (!recoverPlayer()) showError("This stream could not be played.")
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
            cornerRadius = dp(13).toFloat()
            setStroke(dp(if (selected) 2 else 1),
                if (selected) pink else Color.argb(86, 255, 255, 255))
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float = 13f, color: Int = text): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

    // The controls keep 40+ dp touch/focus targets, but the visible surfaces
    // are quiet translucent charcoal rather than opaque colored tiles.
    private fun action(value: String, primary: Boolean = false, width: Int = 42): TextView =
        TextView(this).apply {
            text = value
            textSize = if (primary) 19f else 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(this@PlayerActivity.text)
            background = drawable(
                if (primary) Color.argb(208, 48, 38, 51)
                else Color.argb(188, 26, 23, 31))
            minimumWidth = dp(width)
            minimumHeight = dp(40)
            isClickable = true
            isFocusable = true
            setOnFocusChangeListener { _, focused ->
                background = drawable(
                    if (primary) Color.argb(228, 58, 42, 58)
                    else Color.argb(218, 34, 29, 39), focused)
            }
            setOnClickListener { reveal() }
        }

    private fun buildControls(container: FrameLayout, name: String, description: String) {
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(32), dp(18), dp(32), dp(24))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(155, 9, 8, 13), Color.TRANSPARENT))
        }
        val back = action("‹", width = 40).apply {
            textSize = 27f
            contentDescription = "Back to Kunai"
            setOnClickListener { finish() }
        }
        toolbar.addView(back, LinearLayout.LayoutParams(dp(40), dp(40)))
        val titleWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        titleWrap.addView(label(name, 15f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        titleWrap.addView(label(description, 11f, muted))
        toolbar.addView(titleWrap, LinearLayout.LayoutParams(0, -2, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(35), dp(32), dp(20))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.argb(115, 8, 7, 11),
                    Color.argb(204, 8, 7, 11)))
        }
        // Place compact controls on their own centered row; the timeline has
        // an entire separate row and never fights buttons for available space.
        val buttons = FrameLayout(this)
        val cluster = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val back10 = action("↶ 10").apply {
            contentDescription = "Rewind ten seconds"
            setOnClickListener {
                player?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) }
                recovery.resetForUserSeek(SystemClock.uptimeMillis())
                reveal()
            }
        }
        cluster.addView(back10, LinearLayout.LayoutParams(dp(45), dp(40)))
        cluster.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        val play = action("Ⅱ", primary = true, width = 46).apply {
            contentDescription = "Pause"
            setOnClickListener {
                player?.let { if (it.isPlaying) it.pause() else it.play() }
                reveal()
            }
        }
        playControl = play
        cluster.addView(play, LinearLayout.LayoutParams(dp(46), dp(44)))
        cluster.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        val ahead = action("10 ↷").apply {
            contentDescription = "Forward ten seconds"
            setOnClickListener {
                player?.let { it.seekTo((it.currentPosition + 10_000)
                    .coerceAtMost(it.duration.takeIf { d -> d > 0 } ?: Long.MAX_VALUE)) }
                recovery.resetForUserSeek(SystemClock.uptimeMillis())
                reveal()
            }
        }
        cluster.addView(ahead, LinearLayout.LayoutParams(dp(45), dp(40)))
        buttons.addView(cluster, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        val cc = action("CC", width = 42).apply {
            textSize = 12f
            contentDescription = "Toggle subtitles"
            setOnClickListener {
                captionsEnabled = !captionsEnabled
                if (!captionsEnabled) subtitleOverlay?.setCues(emptyList())
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
        buttons.addView(cc, FrameLayout.LayoutParams(dp(42), dp(40),
            Gravity.END or Gravity.CENTER_VERTICAL))
        bottom.addView(buttons, LinearLayout.LayoutParams(-1, dp(46)))

        val timelineRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val elapsed = label("0:00", 11f, text).apply {
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.argb(225, 246, 239, 244))
        }
        timeText = elapsed
        timelineRow.addView(elapsed, LinearLayout.LayoutParams(dp(58), dp(28)))

        val track = GradientDrawable().apply {
            setColor(Color.argb(115, 255, 255, 255))
            cornerRadius = dp(2).toFloat()
        }
        val played = GradientDrawable().apply {
            setColor(pink)
            cornerRadius = dp(2).toFloat()
        }
        val barDrawable = LayerDrawable(arrayOf(track,
            ClipDrawable(played, Gravity.START, ClipDrawable.HORIZONTAL))).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
            setLayerHeight(0, dp(3))
            setLayerHeight(1, dp(3))
            setLayerGravity(0, Gravity.CENTER_VERTICAL)
            setLayerGravity(1, Gravity.CENTER_VERTICAL)
        }
        val timeline = SeekBar(this).apply {
            max = 1000
            progressDrawable = barDrawable
            thumb = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(pink)
                setSize(dp(9), dp(9))
            }
            splitTrack = false
            isFocusable = true
            contentDescription = "Playback position"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, user: Boolean) {
                    if (user) {
                        val d = player?.duration ?: 0L
                        if (d > 0) timeText?.text = format(d * progress / 1000)
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {
                    scrubbing = true
                    reveal()
                }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    val exo = player
                    if (exo != null && exo.duration > 0)
                        exo.seekTo(exo.duration * (bar?.progress ?: 0) / 1000)
                    recovery.resetForUserSeek(SystemClock.uptimeMillis())
                    saveProgress(durable = true)
                    scrubbing = false
                    reveal()
                }
            })
        }
        timeSeek = timeline
        timelineRow.addView(timeline, LinearLayout.LayoutParams(0, dp(28), 1f))
        val total = label("0:00", 11f, text).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(Color.argb(225, 246, 239, 244))
        }
        durationText = total
        timelineRow.addView(total, LinearLayout.LayoutParams(dp(58), dp(28)))
        bottom.addView(timelineRow, LinearLayout.LayoutParams(-1, -2))

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
                recovery.resetForUserSeek(SystemClock.uptimeMillis())
                player?.prepare();player?.play()
                reveal()
            }
        }
        failure.addView(retry, LinearLayout.LayoutParams(dp(104), dp(45)))
        errorPanel = failure
        container.addView(failure, FrameLayout.LayoutParams(
            dp(320), -2, Gravity.CENTER))
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
        playControl?.contentDescription = if (exo.isPlaying) "Pause" else "Play"
        if (!scrubbing && exo.duration > 0 && exo.duration != C.TIME_UNSET) {
            timeSeek?.progress = (1000 * exo.currentPosition / exo.duration)
                .toInt().coerceIn(0, 1000)
        }
        if (!scrubbing) timeText?.text = format(exo.currentPosition)
        durationText?.text = format(exo.duration)
    }
    private fun showControls(show: Boolean) {
        if (controlsVisible == show && controls?.visibility ==
            (if (show) View.VISIBLE else View.GONE)) return
        controlsVisible = show
        controls?.let { overlay ->
            overlay.animate().cancel()
            if (show) {
                overlay.alpha = 0f
                overlay.visibility = View.VISIBLE
                overlay.animate().alpha(1f).setDuration(155L).start()
            } else {
                overlay.animate().alpha(0f).setDuration(205L).withEndAction {
                    if (!controlsVisible) overlay.visibility = View.GONE
                }.start()
            }
        }
    }
    private fun reveal() {
        lastTouch = SystemClock.uptimeMillis()
        showControls(true)
    }
    private fun recoverPlayer(): Boolean {
        val exo = player ?: return false
        if (!foreground || !exo.playWhenReady) return false
        val now = SystemClock.uptimeMillis()
        if (!recovery.canAttempt(now)) return false
        recovery.attempted(now)
        val position = exo.currentPosition.coerceAtLeast(0)
        saveProgress()
        return try {
            bufferText?.text = "Reconnecting video…"
            bufferText?.visibility = View.VISIBLE
            exo.stop() // Release the stalled local decoder, not the Android app.
            exo.seekTo((position - 10_000L).coerceAtLeast(0))
            exo.prepare()
            exo.playWhenReady = true
            errorPanel?.visibility = View.GONE
            true
        } catch (_: RuntimeException) {
            showError("This stream could not be recovered.")
            false
        }
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
    private fun saveProgress(forced: Long? = null, durable: Boolean = false) {
        val exo = player ?: return
        val currentTitle = title ?: return
        val duration = exo.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
        // An episode that ended stays complete even when the Activity stops.
        val position = forced ?: if (exo.playbackState == Player.STATE_ENDED) 0L
            else exo.currentPosition.coerceAtLeast(0)
        if (duration > 0) library.record(currentTitle, season, episode,
            position.coerceAtMost(duration), duration, durable = durable)
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
    override fun onResume() {
        super.onResume()
        foreground = true
        recovery.resetForUserSeek(SystemClock.uptimeMillis())
    }
    override fun onStop() {
        foreground = false
        saveProgress(durable = true)
        player?.pause()
        super.onStop()
    }
    override fun onDestroy() {
        clock.removeCallbacks(update)
        saveProgress(durable = true)
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
                    .put("language", it.language).put("label", it.label)
                    .put("mimeType", it.mimeType ?: ""))
            }
            data.put("subtitles", tracks)
            host.startActivity(Intent(host, PlayerActivity::class.java)
                .putExtra("stream", data.toString())
                .putExtra("title", Library.encode(title).toString())
                .putExtra("season", season).putExtra("episode", episode))
        }
    }
}
