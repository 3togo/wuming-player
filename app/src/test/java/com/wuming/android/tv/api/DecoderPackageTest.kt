package com.wuming.android.tv.api

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class DecoderPackageTest {
    private fun decode(text: String): String {
        val method = Decoder::class.java.getDeclaredMethod("verify", String::class.java, String::class.java)
        method.isAccessible = true
        return method.invoke(Decoder, "https://example.test/p/index.json", text) as String
    }
    @Test fun normalizesHostedJsonBeforeParsingResourceFields() {
        val result = JsonParser.parseString(decode("\uFEFF  {\"sites\":[{\"api\":\"./main.js\"}]}\n</html>")).asJsonObject
        assertEquals("https://example.test/p/main.js", result.getAsJsonArray("sites")[0].asJsonObject.get("api").asString)
    }
    @Test fun acceptsCommentPrefixedConfigurationAndResolvesResources() {
        val result = JsonParser.parseString(decode("// 小盒子接口说明\n/* 免费分享 */\n{\"sites\":[{\"api\":\"./main.js\"}]}\n</html>")).asJsonObject
        assertEquals("https://example.test/p/main.js", result.getAsJsonArray("sites")[0].asJsonObject.get("api").asString)
    }
    @Test fun liveTextRemainsSupported() {
        val m3u = "#EXTM3U\n#EXTINF:-1,Channel\nhttps://media.example/live.m3u8"
        assertEquals(m3u, decode(m3u))
        val txt = "Channels,#genre#\nChannel,https://media.example/live.m3u8"
        assertEquals(txt, decode(txt))
    }
    @Test fun resourceFieldsResolveWithoutRewritingProviderCode() {
        val result = JsonParser.parseString(decode("""{"sites":[{"key":"x","api":"./js/main.js?rule=../data.json","ext":{"file":"../data.json","code":"return '../literal';"}}]}""")).asJsonObject
        val site = result.getAsJsonArray("sites")[0].asJsonObject
        assertEquals("https://example.test/p/js/main.js?rule=../data.json", site.get("api").asString)
        assertEquals("https://example.test/data.json", site.getAsJsonObject("ext").get("file").asString)
        assertEquals("return '../literal';", site.getAsJsonObject("ext").get("code").asString)
    }
}
