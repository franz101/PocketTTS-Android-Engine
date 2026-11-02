package org.pockettts.android.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

/**
 * In-app model hub: a catalog of downloadable language packs bundled with the app
 * (assets/model_hub.json). Every entry pins its HTTPS URL, byte size and SHA-256,
 * so a download is verified before it reaches the regular ZIP validation and
 * import — ONNX graphs are executed by a native runtime. Downloads run one at a
 * time from a queue. This is the app's only network access; synthesis is offline.
 */
internal object ModelHub {
    const val RECOMMENDED_ID = "english-q4"
    private const val TAG = "PocketTTS"
    private const val URL_PREFIX = "https://pub-f9b8d4e264df41da9c0c5f6b69e46827.r2.dev/"

    enum class Tier { FAST, HQ, EXPERIMENTAL }

    data class Item(
        val id: String,
        val languageTag: String,
        val tier: Tier,
        val url: String,
        val sha256: String,
        val size: Long,
        val recommended: Boolean
    ) {
        val locale: Locale get() = Locale.forLanguageTag(languageTag)
    }

    sealed class State {
        object Queued : State()
        data class Downloading(val done: Long, val total: Long) : State()
        object Installing : State()
    }

    sealed class Event(val id: String) {
        class Progress(id: String, val state: State) : Event(id)
        class Installed(id: String, val pack: ModelPack) : Event(id)
        class Failed(id: String, val message: String) : Event(id)
        class Cancelled(id: String) : Event(id)
    }

    private class CancelledException : IOException("cancelled")

    private val states = ConcurrentHashMap<String, State>()
    private val queue = LinkedBlockingDeque<Pair<Context, Item>>()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<(Event) -> Unit>()
    @Volatile private var cancelRunning: String? = null
    @Volatile private var catalogCache: List<Item>? = null
    private var worker: Thread? = null

    fun catalog(context: Context): List<Item> = catalogCache ?: synchronized(this) {
        catalogCache ?: parse(context.assets.open("model_hub.json").bufferedReader().use { it.readText() })
            .also { catalogCache = it }
    }

    fun find(context: Context, id: String) = catalog(context).firstOrNull { it.id == id }
    fun state(id: String): State? = states[id]
    val isBusy: Boolean get() = states.isNotEmpty()
    fun isInstalled(context: Context, id: String) = ModelPackRepository.find(context, id) != null

    fun addListener(listener: (Event) -> Unit) { listeners += listener }
    fun removeListener(listener: (Event) -> Unit) { listeners -= listener }

    /** True on Wi-Fi/ethernet, i.e. a large download won't use mobile data. */
    fun isUnmetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    @Synchronized
    fun enqueue(context: Context, item: Item) {
        if (states.containsKey(item.id)) return
        publish(Event.Progress(item.id, State.Queued), State.Queued)
        queue.add(context.applicationContext to item)
        if (worker?.isAlive != true) worker = thread(name = "pockettts-model-hub") { drain() }
    }

    @Synchronized
    fun cancel(id: String) {
        if (queue.removeIf { it.second.id == id }) {
            states.remove(id)
            post(Event.Cancelled(id))
        } else if (states[id] is State.Downloading) {
            cancelRunning = id
        }
    }

    private fun drain() {
        while (true) {
            val (app, item) = synchronized(this) { queue.poll() ?: run { worker = null; return } }
            val partial = File(app.cacheDir, "hub-${item.id}.part")
            try {
                val free = app.filesDir.usableSpace
                if (free < item.size * 3) {
                    throw IOException(app.getString(R.string.hub_no_space, UiKit.formatBytes(app, item.size * 3)))
                }
                download(item, partial)
                publish(Event.Progress(item.id, State.Installing), State.Installing)
                val pack = ModelPackRepository.importVerifiedPackFile(app, partial)
                Log.i(TAG, "HUB_INSTALLED id=${pack.id}")
                states.remove(item.id)
                post(Event.Installed(item.id, pack))
            } catch (e: CancelledException) {
                states.remove(item.id)
                post(Event.Cancelled(item.id))
            } catch (error: Throwable) {
                Log.e(TAG, "HUB_FAILED id=${item.id}", error)
                states.remove(item.id)
                post(Event.Failed(item.id, error.message ?: error.javaClass.simpleName))
            } finally {
                partial.delete()
                if (cancelRunning == item.id) cancelRunning = null
            }
        }
    }

    /**
     * Streams [item] into [target], hashing as it goes. Network drops are retried
     * with an HTTP Range request that continues where the previous attempt stopped,
     * so a flaky connection doesn't restart a 200 MB download from zero.
     */
    private fun download(item: Item, target: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        var done = 0L
        var lastPublish = 0L
        var failures = 0
        target.delete()
        while (done < item.size) {
            if (cancelRunning == item.id) throw CancelledException()
            val connection = URL(item.url).openConnection() as HttpURLConnection
            require(connection is HttpsURLConnection) { "Refusing a non-HTTPS download" }
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            if (done > 0) connection.setRequestProperty("Range", "bytes=$done-")
            try {
                val code = connection.responseCode
                val resumed = code == HttpURLConnection.HTTP_PARTIAL &&
                    connection.getHeaderField("Content-Range")?.startsWith("bytes $done-") == true
                if (!resumed) {
                    check(code == HttpURLConnection.HTTP_OK) { "Download failed (HTTP $code)" }
                    if (done > 0) { // server ignored the range: start over
                        done = 0
                        digest.reset()
                        target.delete()
                    }
                }
                connection.inputStream.use { input ->
                    java.io.FileOutputStream(target, true).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            if (cancelRunning == item.id) throw CancelledException()
                            val n = input.read(buffer)
                            if (n < 0) break
                            done += n
                            check(done <= item.size) { "Download is larger than expected" }
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                            failures = 0
                            val now = System.currentTimeMillis()
                            if (now - lastPublish > 200) {
                                lastPublish = now
                                val s = State.Downloading(done, item.size)
                                publish(Event.Progress(item.id, s), s)
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                if (e is CancelledException || ++failures > MAX_RETRIES) throw e
                Log.w(TAG, "HUB_RETRY id=${item.id} at=$done attempt=$failures: ${e.message}")
                Thread.sleep(1000L * failures)
            } finally {
                connection.disconnect()
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        check(hex == item.sha256) { "Download failed its integrity check" }
    }

    private const val MAX_RETRIES = 5

    private fun publish(event: Event, state: State) {
        states[event.id] = state
        post(event)
    }

    private fun post(event: Event) = main.post { listeners.toList().forEach { it(event) } }

    internal fun parse(json: String): List<Item> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Item(
                id = o.getString("id"),
                languageTag = o.getString("languageTag"),
                tier = Tier.valueOf(o.getString("tier").uppercase(Locale.ROOT)),
                url = o.getString("url"),
                sha256 = o.getString("sha256").lowercase(Locale.ROOT),
                size = o.getLong("size"),
                recommended = o.optBoolean("recommended", false)
            ).also {
                require(it.url.startsWith(URL_PREFIX)) { "Untrusted hub URL ${it.url}" }
                require(it.sha256.matches(Regex("[0-9a-f]{64}"))) { "Bad hash for ${it.id}" }
                require(it.size > 0) { "Bad size for ${it.id}" }
            }
        }
    }
}
