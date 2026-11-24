package com.wuming.android.tv.api.loader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor
import com.github.catvod.crawler.SpiderDebug
import com.google.gson.JsonParser
import com.wuming.android.tv.App
import com.wuming.android.tv.api.SiteApi
import com.wuming.android.tv.bean.Site
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpiderDebugInstrumentedTest {
    @Test
    fun stagedProviderHomeReachesCatalogParser() {
        assumeTrue("Requires an explicitly staged provider fixture",
            InstrumentationRegistry.getArguments().getString("provider_load_probe") == "true")
        val fixture = java.io.File(App.get().cacheDir, "provider-load-probe.json")
        val config = JsonParser.parseString(fixture.readText()).asJsonObject
        val site = Site.objectFrom(config.getAsJsonArray("sites")[0], config["spider"].asString)
        val result = SiteApi.homeContent(site)
        assertTrue("Provider categories must reach the parser", result.types.isNotEmpty())
        assertTrue("Provider videos must reach the parser", result.list.isNotEmpty())
    }

    @Test
    fun rawProviderResponsesAreNotFormatStrings() {
        // AI剧 homeVideoContent contains percent-encoded image signatures. Logging
        // must not throw before SiteApi can parse and display the response.
        SpiderDebug.log("SpiderDebugProbe", """{"list":[{"vod_pic":"https://example.invalid/a?sig=a%2Fb%3D","vod_name":"100%好看 %s"}]}""")
        SpiderDebug.log("SpiderDebugProbe", "literal %% and trailing %")
        SpiderDebug.log("SpiderDebugProbe", "resolved %s: %d%%", "fixture", 100)
        SpiderDebug.log("SpiderDebugProbe", null)
        SpiderDebug.log("SpiderDebugProbe", "")
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("logcat -d -s SpiderDebugProbe:D '*:S'")
        val logs = ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { it.readText() }
        assertTrue(logs.contains("a%2Fb%3D"))
        assertTrue(logs.contains("100%好看 %s"))
        assertTrue(logs.contains("literal %% and trailing %"))
        assertTrue(logs.contains("resolved fixture: 100%"))
    }
}
