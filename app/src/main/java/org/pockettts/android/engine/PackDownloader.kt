package org.pockettts.android.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

/**
 * One-tap download of the recommended English pack ("Fast": 4-bit language model,
 * other graphs FP32). The archive is verified against a pinned SHA-256 before it
 * reaches the regular ZIP validation and import, because ONNX graphs are executed
 * by a native runtime. This is the app's only network access; synthesis stays offline.
 */
internal object PackDownloader {
    const val PACK_ID = "english-q4"
    const val VOICE_ID = "alba"
    const val URL_STRING = "https://pub-f9b8d4e264df41da9c0c5f6b69e46827.r2.dev/PocketTTS-english-Q4.zip"
    const val SHA256 = "9b98434c8c56b895e87299aea6afbc01ea84df1f18dd5e7a85c015e447686de5"
    const val SIZE_BYTES = 102_926_223L
    private const val TAG = "PocketTTS"

    sealed class State {
        object Idle : State()
        data class Running(val downloaded: Long, val total: Long) : State()
        object Installing : State()
        data class Failed(val message: String) : State()
        object Done : State()
    }

    @Volatile var state: State = State.Idle
        private set
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<(State) -> Unit>()

    fun isInstalled(context: Context) = ModelPackRepository.find(context, PACK_ID) != null
    val isBusy get() = state is State.Running || state is State.Installing

    /** Replays only an in-progress state; a finished result is announced once, then reset. */
    fun addListener(listener: (State) -> Unit) {
        listeners += listener
        if (isBusy) listener(state)
    }
    fun removeListener(listener: (State) -> Unit) { listeners -= listener }

    /** True on Wi-Fi/ethernet, i.e. a ~100 MB download won't use mobile data. */
    fun isUnmetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun start(context: Context) {
        if (isBusy) return
        val app = context.applicationContext
        publish(State.Running(0, SIZE_BYTES))
        thread(name = "pockettts-pack-download") {
            val partial = File(app.cacheDir, "pack-download.part")
            try {
                download(partial)
                publish(State.Installing)
                val pack = ModelPackRepository.importVerifiedPackFile(app, partial)
                ModelPackRepository.selectPack(app, pack.id)
                pack.voices.firstOrNull { it.id == VOICE_ID }?.let { ModelPackRepository.selectVoice(app, pack.id, it.id) }
                Log.i(TAG, "PACK_DOWNLOAD_DONE id=${pack.id}")
                publish(State.Done)
            } catch (error: Throwable) {
                Log.e(TAG, "PACK_DOWNLOAD_FAILED", error)
                publish(State.Failed(error.message ?: error.javaClass.simpleName))
            } finally {
                partial.delete()
            }
        }
    }

    private fun download(target: File) {
        val connection = URL(URL_STRING).openConnection() as HttpURLConnection
        require(connection is HttpsURLConnection) { "Refusing a non-HTTPS download" }
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = false
        try {
            val code = connection.responseCode
            check(code == HttpURLConnection.HTTP_OK) { "Download failed (HTTP $code)" }
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastPublish = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        done += n
                        check(done <= SIZE_BYTES) { "Download is larger than expected" }
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                        val now = System.currentTimeMillis()
                        if (now - lastPublish > 250) { lastPublish = now; publish(State.Running(done, SIZE_BYTES)) }
                    }
                }
            }
            check(done == SIZE_BYTES) { "Download incomplete" }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            check(hex == SHA256) { "Download failed its integrity check" }
        } finally {
            connection.disconnect()
        }
    }

    private fun publish(next: State) {
        state = next
        main.post {
            listeners.toList().forEach { it(next) }
            if (next is State.Done || next is State.Failed) state = State.Idle
        }
    }
}
