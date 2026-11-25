package com.wuming.tv.core.playback

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.wuming.tv.core.base.Hex
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Anonymous service identity and signed metadata; independent of CatVod, accounts, and platform UI. */
internal class DramaServiceMetadata(
    private val exchange: (Request) -> String = ::execute,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: java.util.Random = SecureRandom(),
) {
    private class Identity(val device: String, val install: String, val registration: String, val client: String) {
        override fun toString() = "AnonymousDramaIdentity"
    }
    private var identity: Identity? = null
    private val signer by lazy { DramaRequestSigning(random, clock()) }

    fun fetch(video: String): String {
        val anonymous = identity()
        val now = clock()
        val fields = profileQuery().apply {
            put("klink_egdi", anonymous.registration); put("iid", anonymous.install)
            put("device_id", anonymous.device); put("cdid", anonymous.client); put("ts", (now / 1000).toString())
        }
        val body = gzip(Gson().toJson(mapOf("content_type" to 1004, "video_id" to video,
            "biz_param" to mapOf("from_video_id" to "", "need_mp4_align" to false,
                "use_server_dns_scene" to "cold_start_stage", "video_platform" to 3,
                "use_os_player" to false, "use_server_dns" to true, "device_level" to 4,
                "disable_video_relate_book" to false, "need_all_video_definition" to true,
                "source" to 4, "detail_page_version" to 0, "disable_digg_stat" to false))).toByteArray(Charsets.UTF_8))
        val signed = signer.sign(fields, body, now)
        val request = Request.Builder().url("$METADATA?${signed.query}")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Content-Encoding", "gzip").header("User-Agent", USER_AGENT).header("Accept", "application/json")
        signed.headers.forEach { (name, value) -> request.header(name, value) }
        return exchange(request.build())
    }

    /** Reuse one registration per client lifetime; failures never publish a partial identity. */
    @Synchronized private fun identity(): Identity {
        identity?.let { return it }
        val now = clock()
        val client = UUID.randomUUID().toString()
        val header = mapOf("display_name" to "红果免费短剧", "update_version_code" to VERSION_CODE,
            "manifest_version_code" to VERSION_CODE, "app_version_minor" to VERSION, "aid" to 8662,
            "channel" to CHANNEL, "package" to "com.phoenix.read", "app_version" to VERSION,
            "version_code" to VERSION_CODE, "os" to "Android", "os_version" to "12", "os_api" to 31,
            "device_model" to MODEL, "device_brand" to BRAND, "device_manufacturer" to BRAND,
            "cpu_abi" to "arm64-v8a", "density_dpi" to 450, "resolution" to "2208x1080",
            "language" to "zh", "timezone" to 8, "access" to "wifi", "not_request_sender" to 0,
            "cdid" to client, "openudid" to Hex.encodeLower(ByteArray(8).also(random::nextBytes)),
            "clientudid" to UUID.randomUUID().toString(), "region" to "CN", "tz_name" to "Asia/Shanghai",
            "tz_offset" to 28800, "req_id" to UUID.randomUUID().toString(), "device_platform" to "android",
            "sdk_flavor" to "china", "guest_mode" to 0)
        val compressed = gzip(Gson().toJson(mapOf("magic_tag" to "ss_app_log", "header" to header,
            "_gen_time" to now)).toByteArray(Charsets.UTF_8))
        val body = registrationEnvelope(compressed, ByteArray(32).also(random::nextBytes))
        val request = Request.Builder().url(REGISTRATION)
            .post(body.toRequestBody("application/octet-stream;tt-data=a".toMediaType()))
            .header("User-Agent", USER_AGENT).header("log-encode-type", "gzip")
            .header("x-ss-stub", Hex.encodeUpper(DramaWire.digest("MD5", body))).header("x-ss-dp", "8662")
            .header("x-ss-req-ticket", now.toString()).header("x-vc-bdturing-sdk-version", "4.0.3.cn").build()
        val parsed = try {
            val root = JsonParser.parseString(exchange(request)).asJsonObject
            fun id(field: String): String = (root.get("${field}_str") ?: root.get(field)).asString.also {
                require(it.matches(Regex("[0-9]{1,30}")) && it.any { c -> c != '0' })
            }
            val registration = root.get("klink_egdi").asString.also { require(it.isNotBlank() && it.length <= 4096) }
            Identity(id("device_id"), id("install_id"), registration, client)
        } catch (_: RuntimeException) {
            throw IOException("The drama service could not register an anonymous playback device. Try again later.")
        }
        identity = parsed
        return parsed
    }

    internal fun registrationEnvelope(compressed: ByteArray, nonce: ByteArray): ByteArray {
        require(nonce.size == 32)
        val derived = DramaWire.digest("SHA-512", DramaWire.digest("SHA-512", nonce) +
            DramaWire.table("registrationSalt").map(Int::toByte).toByteArray())
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived, 0, 16, "AES"), IvParameterSpec(derived, 16, 16))
        return byteArrayOf(116, 99, 5, 16, 0, 0) + nonce + cipher.doFinal(DramaWire.digest("SHA-512", compressed) + compressed)
    }

    private fun profileQuery() = linkedMapOf("ac" to "wifi", "channel" to CHANNEL, "aid" to "8662",
        "app_name" to "novelread", "version_code" to VERSION_CODE.toString(), "version_name" to VERSION,
        "device_platform" to "android", "os" to "android", "ssmix" to "a", "device_type" to MODEL,
        "device_brand" to BRAND, "language" to "zh", "os_api" to "31", "os_version" to "12",
        "manifest_version_code" to VERSION_CODE.toString(), "resolution" to "2208*1080", "dpi" to "450",
        "update_version_code" to VERSION_CODE.toString(), "host_abi" to "arm64-v8a", "dragon_device_type" to "phone",
        "pv_player" to VERSION_CODE.toString(), "compliance_status" to "0", "need_personal_recommend" to "1",
        "player_so_load" to "1", "is_android_pad_screen" to "0", "rom_version" to ROM)

    companion object {
        private const val VERSION_CODE = 73932
        private const val VERSION = "7.3.9.32"
        private const val CHANNEL = "69310258a"
        private const val MODEL = "HD1900"
        private const val BRAND = "OnePlus"
        private const val ROM = "HD1900_11_F.22"
        private const val USER_AGENT = "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN; HD1900; Build/HD1900_11_F.22; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"
        private const val REGISTRATION = "https://log5-applog-lf.fqnovel.com/service/2/device_register/?tt_data=a&use_store_region_cookie=1"
        private const val METADATA = "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/video_model/v1/"
        val shared by lazy { DramaServiceMetadata() }
        private val client by lazy { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build() }
        private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(bytes) }
        }.toByteArray()
        private fun execute(request: Request): String {
            try {
                return client.newCall(request).execute().use {
                    if (!it.isSuccessful) throw IOException("Drama service HTTP ${it.code}")
                    val source = it.body?.source() ?: throw IOException("Empty drama response")
                    if (source.request(2L * 1024 * 1024 + 1)) throw IOException("Drama response exceeds size limit")
                    source.readUtf8()
                }
            } catch (_: IOException) {
                // OkHttp exceptions may include authenticated query parameters.
                throw IOException("The drama service request failed. Try again later.")
            }
        }
    }
}
