package com.wuming.tv.core.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistPurifierTest {

    @Test fun isAd_discontinuity_tag() = assertTrue(HlsPlaylistPurifier.isAd("#EXT-X-DISCONTINUITY"))
    @Test fun isAd_discontinuity_tag_with_content() = assertTrue(HlsPlaylistPurifier.isAd("#EXT-X-DISCONTINUITY\n#EXTINF:10.0,"))
    @Test fun isAd_media_duration_tag() = assertTrue(HlsPlaylistPurifier.isAd("#EXTINF:10.0,"))
    @Test fun isAd_media_duration_tag_with_url() = assertTrue(HlsPlaylistPurifier.isAd("#EXTINF:10.0,\nhttp://example.com/ad.ts"))
    @Test fun isAd_endlist_tag() = assertTrue(HlsPlaylistPurifier.isAd("#EXT-X-ENDLIST"))
    @Test fun isAd_key_tag() = assertTrue(HlsPlaylistPurifier.isAd("#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\""))
    @Test fun isAd_numeric_nonzero() = assertTrue(HlsPlaylistPurifier.isAd("3.14"))
    @Test fun isAd_numeric_negative() = assertTrue(HlsPlaylistPurifier.isAd("-1.5"))
    @Test fun isAd_not_ad_plain_text() = assertFalse(HlsPlaylistPurifier.isAd("hello world"))
    @Test fun isAd_not_ad_ts_url() = assertFalse(HlsPlaylistPurifier.isAd("http://example.com/video.ts"))
    @Test fun isAd_not_ad_relative_path() = assertFalse(HlsPlaylistPurifier.isAd("segment_001.ts"))
    @Test fun isAd_zero_is_not_ad() = assertFalse(HlsPlaylistPurifier.isAd("0"))
    @Test fun isAd_empty_string() = assertFalse(HlsPlaylistPurifier.isAd(""))

    @Test
    fun resolvesRelativeSegmentsAndKeysBeforeApplyingRules() {
        val playlist = "#EXTM3U\n" +
            "#EXT-X-KEY:METHOD=AES-128,URI=\"keys/key.bin\"\n" +
            "#EXTINF:10.0,\nsegment.ts\n"

        val result = HlsPlaylistPurifier.purify("https://media.example/live/", playlist, listOf("unused-rule"))

        assertTrue(result!!.contains("URI=\"https://media.example/live/keys/key.bin\""))
        assertTrue(result.contains("https://media.example/live/segment.ts"))
    }

    @Test
    fun rejectsNonPlaylistContentAndNeedsRulesWhenNoMinorityUrlsExist() {
        assertTrue(HlsPlaylistPurifier.purify("https://media.example/", "not-hls", listOf()) == null)
        assertTrue(HlsPlaylistPurifier.purify("https://media.example/", "#EXTM3U\nsegment.ts\n", listOf()) == null)
    }
}
