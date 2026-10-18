package com.wuming.tv.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubMirrorTest {

    @Test
    fun theDefaultMirrorIsTheOneTheSettingsScreenShows() {
        assertEquals("ghproxy.net", GitHubMirror.hostAt(GitHubMirror.DEFAULT_INDEX))
        assertEquals("", GitHubMirror.hostAt(0))
        assertEquals("ghproxy.net", GitHubMirror.hostAt(-1))
        assertEquals("ghproxy.net", GitHubMirror.hostAt(GitHubMirror.HOSTS.size))
    }

    @Test
    fun remembersAChoiceByHostRatherThanByListPosition() {
        assertEquals("gh-proxy.com", GitHubMirror.hostOf("gh-proxy.com", 1))
        assertEquals("", GitHubMirror.hostOf("", 2))
        assertEquals("v6.gh-proxy.org", GitHubMirror.hostOf(null, 3))
        assertEquals("ghproxy.net", GitHubMirror.hostOf(null, 1))
        assertEquals(5, GitHubMirror.hostIndexOf("gh-proxy.org"))
        assertEquals(-1, GitHubMirror.hostIndexOf("some.other.mirror"))
        assertEquals(-1, GitHubMirror.hostIndexOf("gh-proxy.net"))
    }

    @Test
    fun acceptsEitherABareHostOrAFullPrefix() {
        assertEquals("https://ghproxy.net", GitHubMirror.prefixOf("ghproxy.net"))
        assertEquals("https://ghproxy.net", GitHubMirror.prefixOf("https://ghproxy.net/"))
        assertEquals("http://127.0.0.1:8888", GitHubMirror.prefixOf("http://127.0.0.1:8888"))
        assertEquals("", GitHubMirror.prefixOf("  "))
    }

    @Test
    fun recognizesOnlyGithubContentHosting() {
        assertTrue(GitHubMirror.isGitHubUrl("https://raw.githubusercontent.com/a/b/main/x.json"))
        assertTrue(GitHubMirror.isGitHubUrl("https://github.com/a/b"))
        assertTrue(GitHubMirror.isGitHubUrl("https://gist.githubusercontent.com/a/b/main/x"))
        assertFalse(GitHubMirror.isGitHubUrl("https://github.com.evil.test/a"))
        assertFalse(GitHubMirror.isGitHubUrl("https://mygithubmirror.test/a"))
        assertFalse(GitHubMirror.isGitHubUrl("not a url"))
    }

    @Test
    fun findsThePublishedUrlHiddenInsideSomeoneElsesMirror() {
        assertEquals(
            "https://raw.githubusercontent.com/a/b/main/x.json",
            GitHubMirror.canonical("https://ghproxy.net/https://raw.githubusercontent.com/a/b/main/x.json")
        )
        assertEquals(
            "https://raw.githubusercontent.com/a/b/main/x.json",
            GitHubMirror.canonical("https://gh-proxy.com/https://raw.githubusercontent.com/a/b/main/x.json")
        )
        assertNull(GitHubMirror.canonical("https://host.test/a/b/x.json"))
    }

    @Test
    fun replacesAMirrorBakedIntoTheConfigWithTheOnesThisUserPicked() {
        val configured = "https://gh-proxy.com/https://raw.githubusercontent.com/a/b/main/x.json"
        assertEquals(
            "https://ghproxy.net/https://raw.githubusercontent.com/a/b/main/x.json",
            GitHubMirror.resolve(configured, "ghproxy.net")
        )
        assertEquals(
            "https://raw.githubusercontent.com/a/b/main/x.json",
            GitHubMirror.resolve(configured, "")
        )
    }

    @Test
    fun leavesTheChosenMirrorInPlaceAndIgnoresOtherSources() {
        val proxied = "https://ghproxy.net/https://raw.githubusercontent.com/a/b/main/x.json"
        assertEquals(proxied, GitHubMirror.resolve(proxied, "ghproxy.net"))
        assertEquals(
            proxied,
            GitHubMirror.resolve(proxied, "https://ghproxy.net/")
        )
        assertEquals("https://host.test/live.txt", GitHubMirror.resolve("https://host.test/live.txt", "ghproxy.net"))
        assertEquals(
            "https://ghproxy.net/https://github.com/a/b",
            GitHubMirror.resolve("https://github.com/a/b", "ghproxy.net")
        )
    }

    @Test
    fun keepsMediaAndStreamRequestsOffTheMirror() {
        assertTrue(GitHubMirror.isNeverMirrored("https://raw.githubusercontent.com/a/b/main/video.m3u8"))
        assertTrue(GitHubMirror.isNeverMirrored("https://raw.githubusercontent.com/a/b/main/CLIP.MP4"))
        assertTrue(GitHubMirror.isNeverMirrored("https://host.test/download?file=clip.mp4&auth=1"))
        assertFalse(GitHubMirror.isNeverMirrored("https://raw.githubusercontent.com/a/b/main/config.json"))
        assertFalse(GitHubMirror.isNeverMirrored("https://host.test/api/list"))
    }

    @Test
    fun treatsAnUnparseableOrEmptyUrlAsDirectContent() {
        assertNull(GitHubMirror.embedded("https://host.test/live.txt"))
        assertEquals("https://cdn.test/a", GitHubMirror.embedded("https://host.test/https://cdn.test/a"))
        assertEquals("", GitHubMirror.canonical("   ") ?: "")
        assertEquals("not a url", GitHubMirror.resolve("not a url", "ghproxy.net"))
        assertFalse(GitHubMirror.isNeverMirrored("   "))
    }

    @Test
    fun ignoresALinkWhoseHiddenTargetIsNotAnAddress() {
        assertNull(GitHubMirror.embedded("https://host.test/https://not a url"))
        assertNull(GitHubMirror.embedded("https://host.test/https:///only-a-scheme"))
        assertNull(GitHubMirror.embedded("https://host.test/https://someone@host.test/a"))
        assertNull(GitHubMirror.canonical("https://host.test/https://cdn.test/a"))
    }

    @Test
    fun keepsTheQueryOfAMirrorLinkAndAcceptsItsPrefixAsTheChoice() {
        val configured = "https://gh-proxy.com/https://raw.githubusercontent.com/a/b/x.json?ref=1"
        assertEquals(
            "https://ghproxy.net/https://raw.githubusercontent.com/a/b/x.json?ref=1",
            GitHubMirror.resolve(configured, "ghproxy.net")
        )
        assertEquals(
            configured,
            GitHubMirror.resolve("https://raw.githubusercontent.com/a/b/x.json?ref=1", GitHubMirror.prefixOf("gh-proxy.com"))
        )
    }
}
