package com.wuming.tv.desktop

import com.wuming.tv.core.cloud.OpenDriveItem
import com.wuming.tv.core.runtime.cloudlogin.CloudCredentialStore
import com.wuming.tv.core.runtime.cloudlogin.desktopQrCloudProviders
import com.wuming.tv.core.catalog.CatalogSource
import com.wuming.tv.core.catalog.CatalogFilter
import com.wuming.tv.core.catalog.CatalogFilterSelectionPolicy
import com.wuming.tv.core.catalog.CatalogFilterValue
import com.wuming.tv.core.catalog.CatalogPage
import com.wuming.tv.core.catalog.CatalogType
import com.wuming.tv.core.epg.ChannelNameMatcher
import com.wuming.tv.core.epg.ProgrammeGuideEntry
import com.wuming.tv.core.epg.ProgrammeGuidePolicy
import com.wuming.tv.core.library.ContentKeyCodec
import com.wuming.tv.core.live.LivePlaylistParser
import com.wuming.tv.core.media.MediaFileClassifier
import com.wuming.tv.core.media.PrimaryMediaAvailability
import com.wuming.tv.core.media.PrimaryMediaDestination
import com.wuming.tv.core.media.TvHomeAction
import com.wuming.tv.core.media.TvHomeLayoutSpec
import com.wuming.tv.core.playback.CastQueue
import com.wuming.tv.core.playback.HlsPlaylistPurifier
import com.wuming.tv.core.playback.HlsAdFilterPolicy
import com.wuming.tv.core.playback.HlsAdRule
import com.wuming.tv.core.playback.PlaybackTransportPlan
import com.wuming.tv.core.search.TitleNormalizer
import com.wuming.tv.core.source.SourceMetric
import com.wuming.tv.core.runtime.js.JsSourceSpec
import com.wuming.tv.core.runtime.jvmspider.JvmSpiderSiteSpec
import com.wuming.tv.core.runtime.guardspider.GuardedSpiderAbi
import com.wuming.tv.desktop.jvmspider.GuardedDesktopSupport
import com.wuming.tv.desktop.jvmspider.GuardedDesktopSupportPolicy

data class DesktopSmokeReport(val components: List<String>)

object DesktopSmokeCheck {

    fun verify(): DesktopSmokeReport {
        check(MediaFileClassifier.isVideoFileName("demo.mkv"))
        check(PrimaryMediaAvailability().destinations() == listOf(
            PrimaryMediaDestination.VOD,
            PrimaryMediaDestination.LIVE_TV,
        ))
        check(TvHomeLayoutSpec.desktop().actions.take(3) == listOf(
            TvHomeAction.VOD,
            TvHomeAction.LIVE_TV,
            TvHomeAction.SEARCH,
        ))
        check(PlaybackTransportPlan.inferKind(
            "https://media.example/live.m3u8",
            null,
            false,
        ) == PlaybackTransportPlan.Kind.HLS)
        check(HlsPlaylistPurifier.isAd("#EXT-X-DISCONTINUITY"))
        check(HlsAdFilterPolicy.matchingPatterns(
            "https://media.example/episode.m3u8",
            listOf(HlsAdRule(hosts = listOf("media.example"), patterns = listOf("5.48"))),
        ) == listOf("5.48"))

        val channelMatch = ChannelNameMatcher.match("湖南卫视 HD", "湖南高清")
        check(channelMatch.score == 1.0)
        val current = ProgrammeGuideEntry("demo", "Current programme", 1_000, 2_000)
        check(ProgrammeGuidePolicy.nowNext(listOf(current), 1_500).now == current)

        check(TitleNormalizer.identity().equivalent("Demo Show", "demo-show"))
        check(ContentKeyCodec.contentId("source@@@item") == "item")
        check(SourceMetric(1, 10.0, 1.0, 1.0).totalRequests() == 1L)
        check(OpenDriveItem("item", "Demo", false, 1).name == "Demo")
        val credentials = object : CloudCredentialStore {
            override fun read(providerId: String) = ""
            override fun write(providerId: String, credential: String) = Unit
            override fun remove(providerId: String) = Unit
        }
        check(desktopQrCloudProviders(credentials).map { it.id() } ==
            listOf("alipan", "baidu", "quark", "uc", "bilibili"))
        check(CatalogSource("demo", "Demo source").key == "demo")
        val mediaType = CatalogType(
            id = "movie",
            filters = listOf(CatalogFilter(
                key = "year",
                initialValue = "2026",
                values = listOf(CatalogFilterValue("2026", "2026")),
            )),
        )
        check(CatalogFilterSelectionPolicy.defaults(mediaType) == mapOf("year" to "2026"))
        check(CatalogPage(page = 2, pageCount = 0).hasNext)
        check(CastQueue(listOf(CastQueue.Item("Episode", "id")), 0, "site", "line", "").hasCurrent())
        check(LivePlaylistParser.parse(
            "#EXTM3U\n#EXTINF:-1 group-title=\"Demo\",Demo channel\nhttps://media.example/live.m3u8",
        ).channelCount == 1)
        check(JsSourceSpec("demo", "Demo", "https://example.com/source.js", "secret")
            .toString().contains("<redacted>"))
        check(JvmSpiderSiteSpec(
            key = "demo",
            name = "Demo spider",
            api = "DemoSpider",
            artifactLocation = "https://example.com/spider.jar",
            extension = "secret",
        ).toString().let { "spider.jar" !in it && "secret" !in it })
        check(GuardedSpiderAbi.parse("armeabi-v7a") == GuardedSpiderAbi.ARMEABI_V7A)
        check(GuardedDesktopSupportPolicy.classify(JvmSpiderSiteSpec(
            key = "verified",
            name = "Verified provider",
            api = "csp_NewCzGuard",
            artifactLocation = "https://example.com/spider.jar",
        )) == GuardedDesktopSupport.VERIFIED)

        return DesktopSmokeReport(
            listOf(
                "catalog-core",
                "cloud-core",
                "cloud-login-runtime-core",
                "epg-core",
                "live-core",
                "media-core",
                "playback-core",
                "search-core",
                "source-core",
                "user-library-core",
                "js-runtime-core",
                "jvm-spider-runtime-core",
                "guarded-spider-runtime-core",
            ),
        )
    }
}
