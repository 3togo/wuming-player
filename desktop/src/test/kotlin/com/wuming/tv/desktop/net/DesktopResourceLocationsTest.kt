package com.wuming.tv.desktop.net

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopResourceLocationsTest {

    @Test
    fun githubContentGoesThroughTheMeasuredMirrorByDefault() {
        withMirror(null) {
            assertEquals(
                "https://ghproxy.net/https://raw.githubusercontent.com/a/b/main/config.json",
                ghproxyUrl("https://raw.githubusercontent.com/a/b/main/config.json")
            )
        }
    }

    @Test
    fun theLocalChoiceReplacesTheMirrorBakedIntoAConfig() {
        withMirror("gh-proxy.com") {
            assertEquals(
                "https://gh-proxy.com/https://raw.githubusercontent.com/a/b/main/config.json",
                ghproxyUrl("https://ghproxy.net/https://raw.githubusercontent.com/a/b/main/config.json")
            )
        }
        withMirror("off") {
            assertEquals(
                "https://raw.githubusercontent.com/a/b/main/config.json",
                ghproxyUrl("https://ghproxy.net/https://raw.githubusercontent.com/a/b/main/config.json")
            )
        }
    }

    @Test
    fun leavesContentThatNoMirrorServesAlone() {
        withMirror("ghproxy.net") {
            val other = "https://example.com/tvbox/config.json"
            assertEquals(other, ghproxyUrl(other))
        }
    }

    @Test
    fun triesOurOwnMirrorFirstAndThePublishersUrlForAForeignOne() {
        withMirror("ghproxy.net") {
            assertEquals(
                listOf(
                    "https://ghproxy.net/https://raw.githubusercontent.com/a/b/main/config.json",
                    "https://raw.githubusercontent.com/a/b/main/config.json",
                ),
                embeddedHttpCandidates("https://ghproxy.net/https://raw.githubusercontent.com/a/b/main/config.json")
            )
            assertEquals(
                listOf(
                    "https://raw.githubusercontent.com/a/b/main/config.json",
                    "https://gh-proxy.com/https://raw.githubusercontent.com/a/b/main/config.json",
                ),
                embeddedHttpCandidates("https://gh-proxy.com/https://raw.githubusercontent.com/a/b/main/config.json")
            )
        }
    }

    @Test
    fun keepsAPlainUrlAsItsOnlyCandidate() {
        withMirror("ghproxy.net") {
            assertEquals(
                listOf("https://example.com/live.txt"),
                embeddedHttpCandidates("https://example.com/live.txt")
            )
        }
    }

    private fun withMirror(value: String?, body: () -> Unit) {
        val previous = System.getProperty(PROPERTY)
        try {
            if (value == null) System.clearProperty(PROPERTY) else System.setProperty(PROPERTY, value)
            body()
        } finally {
            if (previous == null) System.clearProperty(PROPERTY) else System.setProperty(PROPERTY, previous)
        }
    }

    private companion object {
        const val PROPERTY = "tvbox.ghproxy"
    }
}
