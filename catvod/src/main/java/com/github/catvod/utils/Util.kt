package com.github.catvod.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.text.TextUtils
import android.util.Base64
import com.github.catvod.Init
import com.github.catvod.Init.context
import java.io.File
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Enumeration
import okhttp3.OkHttp

open class Util {
    // Keep generated static forwarders non-final so existing Java subclasses can hide them.
    @Suppress("NON_FINAL_MEMBER_IN_OBJECT")
    companion object {
        @JvmStatic open fun base64(bytes: ByteArray?): String = base64(
            bytes,
            Base64.DEFAULT or Base64.NO_WRAP
        )

        @JvmStatic open fun decode(s: String?): ByteArray = decode(
            s,
            Base64.DEFAULT or Base64.NO_WRAP
        )

        @JvmStatic open fun substring(text: String?): String? = substring(text, 1)

        @JvmField val OKHTTP: String = "okhttp/" + OkHttp.VERSION
        const val CHROME: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

        @JvmField val URL_SAFE: Int = Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP

        @JvmStatic
        open fun base64(s: String): String = base64(s.toByteArray())

        @JvmStatic
        open fun base64(s: String, flags: Int): String = base64(s.toByteArray(), flags)

        @JvmStatic
        open fun base64(bytes: ByteArray?, flags: Int): String = Base64.encodeToString(bytes, flags)

        @JvmStatic
        open fun decode(s: String?, flags: Int): ByteArray = Base64.decode(s, flags)

        @JvmStatic
        open fun hex2byte(s: String): ByteArray {
            val bytes: ByteArray = ByteArray(s.length / 2)
            for (i: Int in bytes.indices) {
                val hi: Int = s.get(i * 2).digitToIntOrNull(16) ?: -1
                val lo: Int = s.get(i * 2 + 1).digitToIntOrNull(16) ?: -1
                bytes[i] = ((hi shl 4) or lo).toByte()
            }
            return bytes
        }

        @JvmStatic
        open fun equals(name: String?, md5: String?): Boolean =
            md5(Path.jar(name)).equals(md5, ignoreCase = true)

        @JvmStatic
        open fun md5(src: String?): String {
            try {
                if (TextUtils.isEmpty(src)) return ""
                val digest: MessageDigest = MessageDigest.getInstance("MD5")
                val bytes: ByteArray = digest.digest(src!!.toByteArray(StandardCharsets.UTF_8))
                val sb: StringBuilder = StringBuilder(32)
                for (b: Byte in bytes) {
                    sb.append(
                        ((b.toInt() and 0xff) + 0x100).toString(16).substring(1)
                    )
                }
                return sb.toString()
            } catch (e: NoSuchAlgorithmException) {
                return ""
            }
        }

        @JvmStatic
        open fun md5(file: File?): String {
            try {
                FileInputStream(file).use { fis ->
                    val digest: MessageDigest = MessageDigest.getInstance("MD5")
                    val bytes: ByteArray = ByteArray(16384)
                    var count: Int
                    while ((fis.read(bytes).also { count = it }) !=
                        -1
                    ) {
                        digest.update(bytes, 0, count)
                    }
                    val sb: StringBuilder = StringBuilder(32)
                    for (b: Byte in digest.digest()) {
                        sb.append(
                            ((b.toInt() and 0xff) + 0x100).toString(
                                16
                            ).substring(1)
                        )
                    }
                    return sb.toString()
                }
            } catch (e: Exception) {
                return ""
            }
        }

        @JvmStatic
        open fun containOrMatch(text: String?, regex: String?): Boolean {
            try {
                return text!!.contains(regex!!) || text.matches(regex.toRegex())
            } catch (e: Exception) {
                return false
            }
        }

        @JvmStatic
        open fun substring(text: String?, num: Int): String? {
            if (text != null && text.length > num) return text.substring(0, text.length - num)
            return text
        }

        @JvmStatic
        open fun getIp(): String {
            try {
                var ip: String = getHostAddress("wlan")
                if (!ip.isEmpty()) return ip
                ip = getHostAddress("eth")
                if (!ip.isEmpty()) return ip
                ip = wifiAddress
                if (!ip.isEmpty()) return ip
                return getHostAddress("")
            } catch (e: Exception) {
                return ""
            }
        }

        private val wifiAddress: String
            get() {
                val connectivityManager: ConnectivityManager? =
                    context()!!.getSystemService(
                        Context.CONNECTIVITY_SERVICE
                    ) as ConnectivityManager?
                if (connectivityManager == null) return ""
                val network: Network? = connectivityManager.getActiveNetwork()
                if (network == null) return ""
                val capabilities: NetworkCapabilities? =
                    connectivityManager.getNetworkCapabilities(network)
                if (capabilities == null ||
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                ) {
                    return ""
                }
                val linkProperties: LinkProperties? = connectivityManager.getLinkProperties(network)
                if (linkProperties == null) return ""
                for (linkAddress: LinkAddress in linkProperties.getLinkAddresses()) {
                    val address: InetAddress? = linkAddress.getAddress()
                    if (address is Inet4Address &&
                        !address.isLoopbackAddress()
                    ) {
                        return address.getHostAddress() ?: ""
                    }
                }
                return ""
            }

        @Throws(SocketException::class)
        private fun getHostAddress(keyword: String): String {
            val en: Enumeration<NetworkInterface> = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val nif: NetworkInterface = en.nextElement()
                if (!keyword.isEmpty() && !nif.getName().startsWith(keyword)) {
                    continue
                }
                val addresses: Enumeration<InetAddress> = nif.getInetAddresses()
                while (addresses.hasMoreElements()) {
                    val addr: InetAddress = addresses.nextElement()
                    if (!addr.isLoopbackAddress() && addr is Inet4Address) {
                        return addr.getHostAddress() ?: ""
                    }
                }
            }
            return ""
        }
    }
}
