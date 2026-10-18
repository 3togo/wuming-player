package com.github.catvod.spider

import com.wuming.tv.core.runtime.guardspider.GuardedNativeCryptoRuntime
import com.wuming.tv.core.runtime.guardspider.GuardedSpiderAbi
import java.io.IOException
import java.nio.file.Path

/** Desktop implementation of the native methods expected by guarded CatVod providers.  */
object HideUtils {
    private var runtime: GuardedNativeCryptoRuntime? = null

    @Synchronized
    @JvmStatic
    fun configure(
        abiName: String,
        libraryPath: String?,
        guardPath: String?,
        dataDirectory: String?
    ) {
        close()
        val abi: GuardedSpiderAbi = GuardedSpiderAbi.parse(abiName)
        runtime = GuardedNativeCryptoRuntime(
            abi,
            Path.of(libraryPath),
            Path.of(guardPath),
            Path.of(dataDirectory).resolve("native-crypto")
        )
    }

    @get:JvmStatic
    @get:Synchronized
    val isConfigured: Boolean
        get() = runtime != null

    @Synchronized
    @JvmStatic
    fun decrypt(value: String?): String =
        requireRuntime().callString("decrypt(Ljava/lang/String;)Ljava/lang/String;", value)

    @Synchronized
    @JvmStatic
    fun encrypt(value: String?): String =
        requireRuntime().callString("encrypt(Ljava/lang/String;)Ljava/lang/String;", value)

    @Synchronized
    @JvmStatic
    fun tingMd5(value: String?): String = requireRuntime().callString(
        "native_ting_md5(Ljava/lang/String;)Ljava/lang/String;",
        value
    )

    @Synchronized
    @JvmStatic
    fun noxSign(first: String?, second: String?, third: String?): String =
        requireRuntime().callString(
            "noxSign(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            first,
            second,
            third
        )

    @Synchronized
    @JvmStatic
    fun calcResult(value: IntArray): IntArray =
        requireRuntime().callIntArray("calcResult([I)[I", value)

    @Synchronized
    @JvmStatic
    fun close() {
        if (runtime == null) return
        try {
            runtime!!.close()
        } catch (ignored: IOException) {
            // This isolated provider process is releasing its emulator.
        } finally {
            runtime = null
        }
    }

    private fun requireRuntime(): GuardedNativeCryptoRuntime {
        checkNotNull(runtime) { "ARM guard runtime has not been configured" }
        return runtime!!
    }
}
