package com.wuming.tv.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class TvBoxConfigPayloadDecoderTest {

    @Test
    fun acceptsHostedConfigWithLeadingLineAndBlockComments() {
        val json = "{\"sites\":[{\"key\":\"小盒子\",\"api\":\"http://example.test/a/*/b//c\"}]}"
        assertEquals(json, TvBoxConfigPayloadDecoder.decode(
            ("\uFEFF//以下全部出自网络和大佬资源 { }\r\n/* 免费分享 } */\n$json\n</html>").toByteArray()))
    }

    @Test
    fun ignoresCommentBracesInsideTheDocument() {
        val json = "{\n// }\n\"sites\": [/* } { */ {\"key\":\"demo\"}]\n}"
        assertEquals(json, TvBoxConfigPayloadDecoder.trimToJsonDocument("$json</html>"))
    }

    @Test
    fun doesNotExtractObjectsFromCommentedHtmlOrUnterminatedComments() {
        for (text in listOf("// notice\n<html>{}</html>", "/* unfinished {\"sites\":[]}", "// notice\n[{}]")) {
            assertEquals(text, TvBoxConfigPayloadDecoder.trimToJsonDocument(text))
        }
    }

    @Test
    fun acceptsCommentsInsideAnEncodedEnvelope() {
        val json = "{\"sites\":[]}"
        val encoded = Base64.getEncoder().encodeToString(("// notice\n$json").toByteArray())
        assertEquals(json, TvBoxConfigPayloadDecoder.decode(("Ab12Cd34**$encoded").toByteArray()))
    }

    @Test
    fun normalizesWhitespaceAndBomBeforeTrimmingHostedJson() {
        assertEquals("{\"sites\":[]}", TvBoxConfigPayloadDecoder.trimToJsonDocument(
            "  \uFEFF  {\"sites\":[]}\n</html>"))
    }

    @Test
    fun acceptsPlainJson() {
        assertEquals("{\"sites\":[]}", TvBoxConfigPayloadDecoder.decode(
            "  {\"sites\":[]}  ".toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    fun decodesImagePlusBase64EnvelopeWithoutParsingTheImage() {
        val json = "{\"sites\":[{\"key\":\"demo\"}]}"
        val prefix = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x01, 0x02)
        val suffix = ("Ab12Cd34**" + Base64.getEncoder().encodeToString(
            json.toByteArray(StandardCharsets.UTF_8))).toByteArray(StandardCharsets.ISO_8859_1)
        val payload = ByteArray(prefix.size + suffix.size)
        System.arraycopy(prefix, 0, payload, 0, prefix.size)
        System.arraycopy(suffix, 0, payload, prefix.size, suffix.size)

        assertEquals(json, TvBoxConfigPayloadDecoder.decode(payload))
    }

    @Test
    fun acceptsJsonSavedWithAUtf8ByteOrderMark() {
        assertEquals("{\"sites\":[]}", TvBoxConfigPayloadDecoder.decode(
            "\uFEFF{\"sites\":[]}".toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    fun dropsContentAppendedAfterTheConfigurationObject() {
        assertEquals("{\"sites\":[]}", TvBoxConfigPayloadDecoder.decode(
            "{\"sites\":[]}\n</html>".toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    fun keepsTheFirstCompleteObjectWhenAHostAppendsAnother() {
        assertEquals(
            "{\"sites\":[{\"key\":\"a\"}]}",
            TvBoxConfigPayloadDecoder.trimToJsonDocument("{\"sites\":[{\"key\":\"a\"}]}{\"footer\":true}")
        )
    }

    @Test
    fun ignoresBracesInsideStringValuesWhenFindingTheEnd() {
        assertEquals(
            "{\"n\":\"a}b\\\"c\"}",
            TvBoxConfigPayloadDecoder.trimToJsonDocument("{\"n\":\"a}b\\\"c\"}</html>")
        )
    }

    @Test
    fun leavesNonObjectDocumentsAndUnbalancedTextUntouched() {
        assertEquals("[{\"sites\":[]}]junk", TvBoxConfigPayloadDecoder.trimToJsonDocument("[{\"sites\":[]}]junk"))
        assertEquals("<html>{}</html>", TvBoxConfigPayloadDecoder.trimToJsonDocument("<html>{}</html>"))
        assertEquals("{\"sites\":[", TvBoxConfigPayloadDecoder.trimToJsonDocument("{\"sites\":["))
    }

    @Test
    fun rejectsUnknownBinaryPayloads() {
        assertThrows(IllegalArgumentException::class.java) {
            TvBoxConfigPayloadDecoder.decode(byteArrayOf(1, 2, 3))
        }
    }

    @Test
    fun classifiesDirectScriptsWithoutClaimingSpiderJarCompatibility() {
        assertEquals(TvBoxSourceRuntime.DIRECT_JAVASCRIPT,
            TvBoxSourceRuntimePolicy.classify(3, "https://example.test/source.js"))
        assertEquals(TvBoxSourceRuntime.SPIDER_RUNTIME_REQUIRED,
            TvBoxSourceRuntimePolicy.classify(3, "csp_Demo"))
    }

    @Test
    fun classifiesCollectionApisAsMacCmsSources() {
        assertEquals(TvBoxSourceRuntime.MAC_CMS_API,
            TvBoxSourceRuntimePolicy.classify(0, "https://example.test/api/xml.php"))
        assertEquals(TvBoxSourceRuntime.MAC_CMS_API,
            TvBoxSourceRuntimePolicy.classify(1, "http://example.test/api.php/provide/vod/"))
        assertEquals(TvBoxSourceRuntime.UNSUPPORTED_TYPE,
            TvBoxSourceRuntimePolicy.classify(1, "csp_Demo"))
    }

    @Test
    fun classifiesHttpTypeFourAsRemoteSpider() {
        assertEquals(TvBoxSourceRuntime.REMOTE_SPIDER_HTTP,
            TvBoxSourceRuntimePolicy.classify(4, "https://example.test/vod"))
        assertEquals(TvBoxSourceRuntime.REMOTE_SPIDER_HTTP,
            TvBoxSourceRuntimePolicy.classify(4, "./vod"))
        assertEquals(TvBoxSourceRuntime.UNSUPPORTED_TYPE,
            TvBoxSourceRuntimePolicy.classify(4, ""))
    }
}
