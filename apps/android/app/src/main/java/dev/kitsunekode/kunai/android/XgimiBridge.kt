package dev.kitsunekode.kunai.android

import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.ResultReceiver
import dev.kitsunekode.kunai.android.data.Episode
import dev.kitsunekode.kunai.android.data.Title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * Kunai owns the user interaction. XGIMI Remote only retains the paired
 * projector transport, with no duplicate Kunai catalog on its own screens.
 * Never share playback URLs or private pairing credentials between apps.
 */
internal object XgimiBridge {
    private const val PACKAGE = "dev.qwqc.xgimi.remote2"
    private const val RECEIVER = "dev.qwqc.xgimi.KunaiCastReceiver"
    private const val ACTION = "dev.qwqc.xgimi.KUNAI_CAST"

    data class Reply(val ok: Boolean, val message: String)

    suspend fun play(context: Context, title: Title, episode: Episode): Reply =
        send(context, "play") {
            putExtra("id", title.id)
            putExtra("kind", title.kind.key)
            putExtra("title", title.name.take(160))
            putExtra("poster", title.poster)
            putExtra("year", title.year ?: 0)
            putExtra("season", episode.season)
            putExtra("episode", episode.number)
        }

    suspend fun control(context: Context, command: String): Reply {
        require(command in setOf("toggle","back","-30","-10","+10","+30"))
        return send(context, "control") { putExtra("command", command) }
    }

    private suspend fun send(
        context: Context, action: String, extras: Intent.() -> Unit,
    ): Reply = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < 34)
            return@withContext Reply(false, "XGIMI casting requires Android 14 or newer")
        val installed = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(PACKAGE, 0)
        }.isSuccess
        if (!installed)
            return@withContext Reply(false, "Install XGIMI Remote to pair the projector")
        try {
            withTimeout(65_000L) {
                suspendCancellableCoroutine<Reply> { waiting ->
                    val response = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                            if (waiting.isActive) waiting.resume(
                                Reply(resultCode == 1,
                                    resultData?.getString("message") ?: "No projector reply"))
                        }
                    }
                    // Android Intent extras record the concrete Parcelable class.
                    // Sending an anonymous ResultReceiver subclass makes the
                    // companion crash with BadParcelableException because it
                    // cannot load Kunai's private callback class.
                    // Round-trip the binder through the platform CREATOR so
                    // only android.os.ResultReceiver is sent across apps.
                    val parcel = Parcel.obtain()
                    val transportReply = try {
                        response.writeToParcel(parcel, 0)
                        parcel.setDataPosition(0)
                        ResultReceiver.CREATOR.createFromParcel(parcel)
                    } finally {
                        parcel.recycle()
                    }
                    val request = Intent(ACTION).apply {
                        setClassName(PACKAGE, RECEIVER)
                        putExtra("action", action)
                        putExtra("reply", transportReply)
                        extras()
                    }
                    try {
                        // Android 14+ does not expose the sender UID unless
                        // the sender explicitly enables identity sharing.
                        // The companion rejects all requests without this UID.
                        val options = BroadcastOptions.makeBasic()
                            .setShareIdentityEnabled(true).toBundle()
                        context.sendBroadcast(request, null, options)
                    } catch (failure: RuntimeException) {
                        if (waiting.isActive) waiting.resume(
                            Reply(false, "Unable to contact XGIMI Remote"))
                    }
                }
            }
        } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
            Reply(false, "Projector did not confirm playback or command")
        }
    }
}
