package com.wuming.tv.guardspider

import android.R
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.github.catvod.crawler.Spider
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wuming.tv.core.runtime.guardspider.SpiderInvocationSupport
import com.wuming.tv.core.runtime.guardspider.SpiderInvocationSupport.attachPlaybackCookies
import dalvik.system.DexClassLoader
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.HashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.Volatile

/**
 * Foreground service that runs a GMS-dependent guarded provider on a real Android runtime (which has
 * Google Play Services the desktop JVM lacks) and relays the raw spider JSON back to the desktop
 * guarded host over an `adb forward` TCP tunnel.
 *
 * This is the device-side half of `AdbAndroidGmsProviderBridge`: the desktop pushes the
 * *prepared* spider artifact (already decrypted by the guarded preparer) to
 * `/data/local/tmp/tvbox-guarded/spider.jar`, starts this service, and forwards
 * `tcp:8766`. Each desktop op arrives as one line-framed JSON message; this service loads the
 * artifact via a [DexClassLoader] (parented on the app classloader that carries the catvod
 * crawler framework) and runs the op, returning `{"ok":bool,"result":<raw spider json>}`.
 *
 * The op logic mirrors `com.wuming.tv.guardhost.GuardedCatVodHostMain` exactly.
 */
open class SpiderHelperService : Service() {
    private val sessions: MutableMap<String?, ProviderSession?> =
        ConcurrentHashMap<String?, ProviderSession?>()

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        try {
            server = ServerSocket(PORT)
        } catch (e: IOException) {
            stopSelf()
            return
        }
        Thread(Runnable { this.acceptLoop() }, "guardspider-accept").start()
    }

    private fun acceptLoop() {
        while (!stopped && server != null && !server!!.isClosed()) {
            try {
                val socket: Socket = server!!.accept()
                Connection(socket).start()
            } catch (e: IOException) {
                break
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ bounds dataSync foreground-service time for targets 35+.
        stopSelf()
    }

    override fun onDestroy() {
        stopped = true
        try {
            if (server != null) server!!.close()
        } catch (ignored: IOException) {
            // best effort
        }
        super.onDestroy()
    }

    /**
     * Android refuses to load a dex from a world-writable path, so the artifact the desktop pushes
     * into `/data/local/tmp` is copied into this app's private storage before it is loaded.
     */
    @Throws(IOException::class)
    private fun copyToPrivate(name: String, sourcePath: String): File {
        val src: File = File(sourcePath)
        if (!src.exists()) throw IOException("artifact missing: " + sourcePath)
        val dst: File = File(getFilesDir(), name)
        if (!dst.exists() || dst.lastModified() != src.lastModified() ||
            dst.length() != src.length()
        ) {
            if (dst.exists()) {
                dst.setWritable(true, false)
                dst.delete()
            }
            copyFile(src, dst)
        }
        dst.setReadOnly()
        return dst
    }

    @Throws(IOException::class)
    private fun copyNativeLib(): File? {
        val src: File = File(DEVICE_NATIVE_PATH)
        if (!src.exists()) return null
        val dir: File = File(getFilesDir(), "lib")
        dir.mkdirs()
        val dst: File = File(dir, "guard.so")
        if (!dst.exists() || dst.lastModified() != src.lastModified()) {
            if (dst.exists()) {
                dst.setWritable(true, false)
                dst.delete()
            }
            copyFile(src, dst)
        }
        dst.setReadOnly()
        return dir
    }

    private fun startForegroundNotification() {
        val nm: NotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel: NotificationChannel = NotificationChannel(
                CHANNEL_ID,
                "Spider Helper",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Spider Helper")
            .setContentText("Running GMS-dependent providers for the desktop guarded host")
            .setSmallIcon(R.drawable.ic_dialog_info)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    /** One per desktop connection; loads (once) and runs a single provider op.  */
    private inner class Connection(private val socket: Socket) : Thread() {
        override fun run() {
            try {
                socket.use {
                    BufferedReader(
                        InputStreamReader(
                            socket.getInputStream(),
                            StandardCharsets.UTF_8
                        )
                    ).use { `in` ->
                        PrintWriter(
                            OutputStreamWriter(
                                socket.getOutputStream(),
                                StandardCharsets.UTF_8
                            ),
                            true
                        ).use { out ->
                            var line: String? = null
                            while (!stopped && (`in`.readLine().also { line = it }) != null) {
                                handle(line, out)
                            }
                        }
                    }
                }
            } catch (ignored: IOException) {
                // connection closed
            }
        }

        fun handle(line: String?, out: PrintWriter) {
            val message: JsonObject
            try {
                message = JsonParser.parseString(line).getAsJsonObject()
            } catch (e: Exception) {
                writeEnvelope(out, false, null, "invalid request json")
                return
            }
            val request: JsonObject? =
                if (message.has("request")) message.getAsJsonObject("request") else JsonObject()
            val className: String = str(message, "className")
            val key: String = str(message, "key")
            val extension: String = str(message, "extension")
            val operation: String = str(request, "operation")
            if (className.isEmpty()) {
                writeEnvelope(out, false, null, "missing className")
                return
            }
            try {
                val session: ProviderSession = session(className, key, extension)
                val result: String = invoke(session, operation, request)
                writeEnvelope(out, true, result, null)
            } catch (failure: Throwable) {
                val cause: Throwable = rootCause(failure)
                writeEnvelope(
                    out,
                    false,
                    null,
                    cause.javaClass.getSimpleName() + ": " + cause.message.toString()
                )
            }
        }

        @Throws(Exception::class)
        fun session(className: String, key: String, extension: String): ProviderSession {
            val existing: ProviderSession? = sessions.get(className)
            if (existing != null) return existing
            synchronized(sessions) {
                val cached: ProviderSession? = sessions.get(className)
                if (cached != null) return cached
                val context: Context? = getApplicationContext()
                val privateJar: File = copyToPrivate("spider.jar", DEVICE_SPIDER_PATH)
                val nativeLibDir: File? = copyNativeLib()
                val loader: DexClassLoader = DexClassLoader(
                    privateJar.getAbsolutePath(),
                    getCodeCacheDir().getAbsolutePath() + "/spider-odex",
                    if (nativeLibDir != null) nativeLibDir.getAbsolutePath() else null,
                    this@SpiderHelperService.getClassLoader()
                )
                val spider: Any =
                    Class.forName(className, true, loader).getDeclaredConstructor().newInstance()
                setSiteKey(spider, key)
                initializeOrigin(loader, context)
                if (!extension.isEmpty()) {
                    val init: Method? =
                        find(spider, "init", Context::class.java, String::class.java)
                    if (init != null) {
                        init.invoke(spider, context, extension)
                    } else {
                        required(spider, "init", Context::class.java).invoke(spider, context)
                    }
                }
                val created: ProviderSession = ProviderSession(loader, spider, context)
                sessions.put(className, created)
                return created
            }
        }

        fun writeEnvelope(out: PrintWriter, ok: Boolean, result: String?, error: String?) {
            val envelope: JsonObject = JsonObject()
            envelope.addProperty("ok", ok)
            if (result != null) envelope.addProperty("result", result)
            if (error != null) envelope.addProperty("error", error)
            synchronized(out) {
                out.println(GSON.toJson(envelope))
            }
        }

        fun str(`object`: JsonObject?, name: String?): String =
            if (`object` != null && `object`.has(name) && `object`.get(name)
                    .isJsonPrimitive()
            ) {
                `object`.get(name).getAsString()
            } else {
                ""
            }
    }

    private class ProviderSession(
        val loader: DexClassLoader?,
        val spider: Any,
        val context: Context?
    )

    companion object {
        private const val PORT = 8766
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "guardspider"
        private const val DEVICE_SPIDER_PATH = "/data/local/tmp/tvbox-guarded/spider.jar"
        private const val DEVICE_NATIVE_PATH = "/data/local/tmp/tvbox-guarded/guard.so"

        private val GSON = Gson()

        @Throws(IOException::class)
        private fun copyFile(src: File?, dst: File) {
            val parent: File? = dst.getParentFile()
            if (parent != null) parent.mkdirs()
            FileInputStream(src).use { `in` ->
                FileOutputStream(dst).use { out ->
                    val buf: ByteArray = ByteArray(1 shl 16)
                    var n: Int
                    while ((`in`.read(buf).also { n = it }) > 0) out.write(buf, 0, n)
                }
            }
        }

        // --- Provider invocation (mirrors GuardedCatVodHostMain.invoke) ---
        @Throws(Exception::class)
        private fun invoke(
            session: ProviderSession,
            operation: String,
            request: JsonObject?
        ): String {
            val spider: Any = session.spider
            val context: Context? = session.context
            return when (operation) {
                "init" -> "{}"

                "home" -> value(
                    required(
                        spider,
                        "homeContent",
                        Boolean::class.javaPrimitiveType!!
                    ).invoke(spider, true)
                )

                "homeVideo" -> value(required(spider, "homeVideoContent").invoke(spider))

                "category" -> value(
                    required(
                        spider,
                        "categoryContent",
                        String::class.java,
                        String::class.java,
                        Boolean::class.javaPrimitiveType!!,
                        HashMap::class.java
                    )
                        .invoke(
                            spider,
                            Companion.value(str(request, "id")),
                            value(str(request, "page"), "1"),
                            true,
                            HashMap<String?, String?>(filters(request))
                        )
                )

                "search" -> value(
                    required(
                        spider,
                        "searchContent",
                        String::class.java,
                        Boolean::class.javaPrimitiveType!!
                    )
                        .invoke(spider, Companion.value(str(request, "query")), false)
                )

                "detail" -> value(
                    required(spider, "detailContent", MutableList::class.java)
                        .invoke(
                            spider,
                            java.util.List.of<String?>(Companion.value(str(request, "id")))
                        )
                )

                "play" -> {
                    var target: Any? =
                        findCloudPlayer(spider, str(request, "flag"), str(request, "id"))
                    if (target == null) target = spider
                    val result: String = value(
                        required(
                            target,
                            "playerContent",
                            String::class.java,
                            String::class.java,
                            MutableList::class.java
                        )
                            .invoke(
                                target,
                                Companion.value(str(request, "flag")),
                                Companion.value(str(request, "id")),
                                java.util.List.of<Any?>()
                            )
                    )
                    attachPlaybackCookies(result)
                }

                "close" -> "{}"

                else -> throw IllegalArgumentException("Unsupported guarded host operation")
            }
        }

        private fun filters(request: JsonObject?): MutableMap<String?, String?> {
            val map: MutableMap<String?, String?> = HashMap<String?, String?>()
            if (request != null && request.has("filters") && request.get("filters")
                    .isJsonObject()
            ) {
                val filters: JsonObject = request.getAsJsonObject("filters")
                for (name: String? in filters.keySet()) {
                    map.put(name, filters.get(name).getAsString())
                }
            }
            return map
        }

        private fun str(request: JsonObject?, name: String?): String? =
            if (request != null && request.has(name) && request.get(name).isJsonPrimitive()) {
                request.get(name).getAsString()
            } else {
                ""
            }

        /**
         * Leanback resolves and plays a provider URL through one shared CookieJar. The device helper is
         * intentionally isolated, so copy only cookies which OkHttp would send to the resolved media
         * URL into CatVod's existing header field before returning across the tunnel.
         */
        @JvmStatic
        fun attachPlaybackCookies(result: String): String =
            SpiderInvocationSupport.attachPlaybackCookies(result) { url: String? ->
                Spider.cookieHeader(url)
            }

        private fun setSiteKey(spider: Any, siteKey: String) {
            SpiderInvocationSupport.setSiteKey(spider, siteKey)
        }

        @Throws(Exception::class)
        private fun initializeOrigin(loader: ClassLoader?, context: Context?) {
            try {
                val type: Class<*> =
                    Class.forName("com.github.catvod.spider.InitOrigin", true, loader)
                val singleton: Any? = type.getMethod("get").invoke(null)
                for (field: Field in type.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) &&
                        Application::class.java.isAssignableFrom(
                            field.getType()
                        )
                    ) {
                        field.setAccessible(true)
                        field.set(singleton, context)
                        return
                    }
                }
            } catch (ignored: ClassNotFoundException) {
                // Providers which do not contain the guarded bootstrap need no global context.
            }
        }

        private fun required(spider: Any, name: String, vararg parameters: Class<*>): Method =
            SpiderInvocationSupport.required(spider, name, *parameters)

        private fun findCloudPlayer(spider: Any, flag: String?, address: String?): Any? =
            SpiderInvocationSupport.findCloudPlayer(spider, flag, address)

        @JvmStatic
        fun cloudPlayerField(value: String): String? =
            SpiderInvocationSupport.cloudPlayerField(value)

        private fun findField(type: Class<*>, name: String): Field? =
            SpiderInvocationSupport.findField(type, name)

        private fun find(spider: Any, name: String, vararg parameters: Class<*>): Method? =
            SpiderInvocationSupport.find(spider, name, *parameters)

        private fun rootCause(failure: Throwable): Throwable =
            SpiderInvocationSupport.rootCause(failure)

        private fun value(value: Any?): String = SpiderInvocationSupport.value(value)

        private fun value(value: String?): String = SpiderInvocationSupport.value(value)

        private fun value(value: String?, fallback: String): String =
            SpiderInvocationSupport.value(value, fallback)
    }
}
