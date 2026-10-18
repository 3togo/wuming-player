package com.wuming.tv.core.playback

import org.junit.Assert.*
import org.junit.Test
import java.util.function.Function

class DashRelayManifestTest {
    @Test fun normalizesMeasuredProviderMetadataWithoutInventingAudioSampleRates() {
        val xml = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period><AdaptationSet><Representation id="80_7" bandwidth="976569" mimeType="video/mp4" codecs="avc1.640033" frameRate="29.992"><BaseURL>https://media.example/video.m4s</BaseURL><SegmentBase indexRange="945-21036"><Initialization range="0-944"/></SegmentBase></Representation></AdaptationSet><AdaptationSet><Representation id="30280_0" bandwidth="101143" mimeType="audio/mp4" codecs="mp4a.40.2" numChannels="2" sampleRate="192000"><BaseURL>https://media.example/audio.m4s</BaseURL><SegmentBase indexRange="837-20736"><Initialization range="0-836"/></SegmentBase></Representation></AdaptationSet></Period></MPD>"""
        val result = DashRelayManifest.rewrite(xml, "http://phone/source.mpd", Function { it }, true)
        assertEquals(3, result.normalizedMetadataFields)
        assertTrue(result.manifest.contains("frameRate=\"3749/125\""))
        assertFalse(result.manifest.contains("sampleRate="))
        assertFalse(result.manifest.contains("audioSamplingRate="))
        assertFalse(result.manifest.contains("numChannels="))
        assertTrue(result.manifest.contains("AudioChannelConfiguration"))
        assertTrue(result.manifest.contains("urn:mpeg:dash:23003:3:audio_channel_configuration:2011"))
        assertTrue(result.manifest.contains("value=\"2\""))
        assertTrue(result.manifest.contains("indexRange=\"945-21036\""))
        assertTrue(result.manifest.contains("range=\"0-836\""))
        assertEquals(xml, DashRelayManifest.rewrite(xml, "http://phone/source.mpd", Function { it }).manifest)
    }

    @Test fun preservesStandardAudioMetadataAndFractionalFrameRates() {
        val xml = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period><AdaptationSet frameRate="30.000"><Representation frameRate="30000/1001"/></AdaptationSet><AdaptationSet mimeType="audio/mp4"><Representation numChannels="6" sampleRate="192000" audioSamplingRate="48000"><AudioChannelConfiguration schemeIdUri="existing" value="layout"/></Representation></AdaptationSet></Period></MPD>"""
        val result = DashRelayManifest.rewrite(xml, "http://phone/source.mpd", Function { it }, true)
        assertTrue(result.manifest.contains("frameRate=\"30\""))
        assertTrue(result.manifest.contains("frameRate=\"30000/1001\""))
        assertTrue(result.manifest.contains("audioSamplingRate=\"48000\""))
        assertTrue(result.manifest.contains("schemeIdUri=\"existing\""))
        assertFalse(result.manifest.contains("sampleRate="))
        assertFalse(result.manifest.contains("numChannels="))
    }

    @Test fun relaysSignedExternalAudioAndVideoWithoutChangingRangesOrMetadata() {
        val seen = mutableListOf<String>()
        val xml = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:x="http://metadata.example/ns"><Period><AdaptationSet><Representation codecs="avc1.640033"><BaseURL>https://cdn.example/video.m4s?a=1&amp;b=2</BaseURL><SegmentBase indexRange="100-200"><Initialization range="0-99"/></SegmentBase></Representation><Representation><BaseURL>audio.m4s</BaseURL><SegmentBase indexRange="10-20"/></Representation></AdaptationSet></Period></MPD>"""
        val result = DashRelayManifest.rewrite(xml, "https://origin.example/path/source.mpd", Function {
            seen.add(it); "http://phone/cast-relay/token/u/${seen.size}"
        })
        assertEquals(listOf("https://cdn.example/video.m4s?a=1&b=2", "https://origin.example/path/audio.m4s"), seen)
        assertEquals(2, result.rewrittenUrls)
        assertTrue(result.manifest.contains("indexRange=\"100-200\""))
        assertTrue(result.manifest.contains("range=\"0-99\""))
        assertTrue(result.manifest.contains("http://metadata.example/ns"))
        assertTrue(result.manifest.contains("avc1.640033"))
    }
    @Test fun compatibilityPrefersAvcOnlyWhenAnAvcAlternativeExists() {
        val xml = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period><AdaptationSet><Representation codecs="hvc1.1"/><Representation codecs="avc1.640033"/><Representation codecs="av01.0"/></AdaptationSet><AdaptationSet><Representation codecs="mp4a.40.2"/></AdaptationSet></Period></MPD>"""
        val filtered = DashRelayManifest.rewrite(xml, "http://host/a.mpd", Function { it }, true)
        assertEquals(2, filtered.removedVideoRepresentations)
        assertTrue(filtered.manifest.contains("avc1.640033")); assertTrue(filtered.manifest.contains("mp4a.40.2"))
        assertFalse(filtered.manifest.contains("hvc1")); assertFalse(filtered.manifest.contains("av01"))
        val hevcOnly = xml.replace("<Representation codecs=\"avc1.640033\"/>", "")
        assertEquals(hevcOnly, DashRelayManifest.rewrite(hevcOnly, "http://host/a.mpd", Function { it }, true).manifest)
        assertEquals(xml, DashRelayManifest.rewrite(xml, "http://host/a.mpd", Function { it }).manifest)
    }
    @Test fun prefersAvcAcrossBilibiliAdaptationSetsButNotAcrossPeriods() {
        val xml = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period><AdaptationSet><Representation codecs="hvc1.1"/></AdaptationSet><AdaptationSet><Representation codecs="avc1.640033"/></AdaptationSet><AdaptationSet><Representation codecs="av01.0"/></AdaptationSet><AdaptationSet><Representation codecs="mp4a.40.2"/></AdaptationSet></Period><Period><AdaptationSet><Representation codecs="hvc1.2"/></AdaptationSet></Period></MPD>"""
        val result = DashRelayManifest.rewrite(xml, "http://host/a.mpd", Function { it }, true)
        assertEquals(2, result.removedVideoRepresentations)
        assertFalse(result.manifest.contains("hvc1.1"))
        assertFalse(result.manifest.contains("av01.0"))
        assertTrue(result.manifest.contains("hvc1.2"))
        assertTrue(result.manifest.contains("mp4a.40.2"))
        assertFalse(result.manifest.contains("<AdaptationSet/>"))
    }
    @Test fun leavesDirectoryBasesAndTemplatesIntact() {
        val xml = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><BaseURL>https://cdn.example/</BaseURL><Period><Representation><SegmentTemplate media="chunk-${'$'}Number${'$'}.m4s"/></Representation></Period></MPD>"""
        val result = DashRelayManifest.rewrite(xml, "https://origin.example/a.mpd", Function { fail("Template directory is not a file URL"); it })
        assertEquals(xml, result.manifest)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsExternalEntities() {
        DashRelayManifest.rewrite("<!DOCTYPE MPD [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><MPD>&x;</MPD>", "http://host/a.mpd", Function { it })
    }
    @Test fun providerHeadersAreScopedAndDoNotOverwriteExplicitValues() {
        val root = "http://127.0.0.1:9978/proxy?do=bili&type=mpd"
        val headers = DashRelayPolicy.headers(root, "https://upos.bilivideo.com/audio.m4s", mapOf("Cookie" to "private", "Authorization" to "private"))
        assertEquals("https://www.bilibili.com/", headers["Referer"])
        assertEquals("Mozilla/5.0", headers["User-Agent"])
        assertFalse(headers.containsKey("Cookie")); assertFalse(headers.containsKey("Authorization"))
        assertTrue(DashRelayPolicy.headers(root, "https://bilivideo.com.evil.example/a", emptyMap()).isEmpty())
        assertTrue(DashRelayPolicy.headers("https://other/a.mpd", "https://upos.bilivideo.com/a", emptyMap()).isEmpty())
        assertEquals("custom", DashRelayPolicy.headers(root, "https://upos.bilivideo.com/a", mapOf("referer" to "custom"))["referer"])
        assertTrue(DashRelayPolicy.isManifest(root)); assertTrue(DashRelayPolicy.isManifest("https://host/a.mpd?q=1"))
        assertFalse(DashRelayPolicy.isManifest("https://host/a.mp4"))
    }
}
