package com.wuming.tv.core.design.update

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class UpdateManifestTest {
    private fun manifest(): String = """{
      "schema":1,"channel":"debug","code":642,"name":"testing","desc":"Testing build",
      "artifacts":[{"mode":"mobile","abi":"arm64_v8a","packageName":"com.wuming.android.tv",
      "url":"https://github.com/3togo/TV-updates/releases/download/testing-642-debug/mobile.apk",
      "sha256":"${"a".repeat(64)}","certificateSha256":"${"b".repeat(64)}"}]}"""

    private fun select(json: String = manifest(), channel: String = "debug", abi: String = "arm64_v8a",
                       pkg: String = "com.wuming.android.tv", code: Long = 641) =
        UpdateManifest.select(json, "3togo/TV-updates", channel, "mobile", abi, pkg, code)

    @Test fun selectsNewerMatchingArtifactAndRejectsDowngrades() {
        assertEquals(642, select()!!.code)
        assertNull(select(code = 642))
        assertNull(select(code = 643))
    }

    @Test fun acceptsProjectGiteeMirror() {
        val mirrored = manifest().replace("github.com/3togo", "gitee.com/freetogo")
        assertEquals(642, select(mirrored)!!.code)
        assertNull(select(mirrored, code = 650))
    }

    @Test fun ignoresStaleFeedBeforeInspectingDownloads() {
        val stale = manifest().replace("github.com/3togo", "obsolete.example/old")
        assertNull(select(stale, code = 642))
        assertNull(select(stale, code = 650))
    }

    @Test fun requiresMatchingChannelAbiAndPackage() {
        assertThrows(IllegalArgumentException::class.java) { select(channel = "release") }
        assertThrows(IllegalArgumentException::class.java) { select(abi = "armeabi_v7a") }
        assertThrows(IllegalArgumentException::class.java) { select(pkg = "com.wuming.android.tv.verification") }
        assertThrows(IllegalArgumentException::class.java) { select(channel = "verification") }
    }

    @Test fun rejectsUntrustedDownloadsAndMalformedDigests() {
        for (url in listOf("http://github.com/3togo/TV-updates/releases/download/v/a.apk",
            "https://github.com/other/repo/releases/download/v/a.apk",
            "https://gitee.com/other/repo/releases/download/v/a.apk",
            "https://gitee.com/freetogo/TV-updates-evil/releases/download/v/a.apk",
            "https://gitee.com/freetogo/TV-updates/releases/download/../a.apk",
            "https://github.com.evil.test/3togo/TV-updates/releases/download/v/a.apk",
            "https://github.com/3togo/TV-updates/releases/download/../a.apk")) {
            val root = JsonParser.parseString(manifest()).asJsonObject
            root["artifacts"].asJsonArray[0].asJsonObject.addProperty("url", url)
            assertThrows(IllegalArgumentException::class.java) { select(root.toString()) }
        }
        assertThrows(IllegalArgumentException::class.java) { select(manifest().replace("a".repeat(64), "invalid")) }
    }

    @Test fun rejectsDuplicateMatchingArtifacts() {
        val root = JsonParser.parseString(manifest()).asJsonObject
        root["artifacts"].asJsonArray.add(root["artifacts"].asJsonArray[0].deepCopy())
        assertThrows(IllegalArgumentException::class.java) { select(root.toString()) }
    }

    @Test fun choosesNewestEvenWhenStaleMirrorRespondsFirst() {
        val old = select()!!
        val newer = old.copy(code = 643)
        assertEquals(643, UpdateManifest.newest(listOf(old, newer))!!.code)
        assertNull(UpdateManifest.newest(emptyList()))
    }

    @Test fun mergesIdenticalMirrorsAndRemembersSuccessfulHost() {
        val github = select()!!
        val gitee = select(manifest().replace("github.com/3togo", "gitee.com/freetogo"))!!
        val merged = UpdateManifest.newest(listOf(github, gitee), "gitee.com")!!
        assertEquals(gitee.url, merged.url)
        assertEquals(listOf(gitee.url, github.url), merged.urls)
    }

    @Test fun rejectsConflictingSameVersionMirrors() {
        val first = select()!!
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifest.newest(listOf(first, first.copy(sha256 = "c".repeat(64))))
        }
    }

    @Test fun rejectsInvalidJson() {
        assertThrows(IllegalArgumentException::class.java) { select("{not json") }
        assertThrows(IllegalArgumentException::class.java) { select("[]") }
        assertThrows(IllegalArgumentException::class.java) { select("null") }
        assertThrows(IllegalArgumentException::class.java) { select("") }
    }

    @Test fun handlesMissingFieldsGracefully() {
        val noCode = JsonParser.parseString(manifest()).asJsonObject.apply { remove("code") }
        assertThrows(IllegalArgumentException::class.java) { select(noCode.toString()) }

        val noArtifacts = JsonParser.parseString(manifest()).asJsonObject
        noArtifacts.add("artifacts", com.google.gson.JsonArray())
        assertThrows(IllegalArgumentException::class.java) { select(noArtifacts.toString()) }

        val noUrl = JsonParser.parseString(manifest()).asJsonObject
        noUrl["artifacts"].asJsonArray[0].asJsonObject.remove("url")
        assertThrows(IllegalArgumentException::class.java) { select(noUrl.toString()) }

        val noSha256 = JsonParser.parseString(manifest()).asJsonObject
        noSha256["artifacts"].asJsonArray[0].asJsonObject.remove("sha256")
        assertThrows(IllegalArgumentException::class.java) { select(noSha256.toString()) }
    }

    @Test fun handlesNonObjectArtifactsGracefully() {
        val root = JsonParser.parseString(manifest()).asJsonObject
        root["artifacts"].asJsonArray[0] = JsonParser.parseString("\"not-an-object\"")
        assertThrows(IllegalArgumentException::class.java) { select(root.toString()) }
    }

    @Test fun defaultsNameAndDescToEmptyWhenAbsent() {
        val root = JsonParser.parseString(manifest()).asJsonObject
        root.remove("name")
        root.remove("desc")
        val artifact = select(root.toString())!!
        assertEquals("", artifact.name)
        assertEquals("", artifact.description)
    }

    @Test fun validatesEveryFallbackUrlAndRetainsLegacyManifests() {
        val root = JsonParser.parseString(manifest()).asJsonObject
        val asset = root["artifacts"].asJsonArray[0].asJsonObject
        val mirrors = com.google.gson.JsonArray()
        mirrors.add("https://gitee.com/freetogo/TV-updates/releases/download/testing-642-debug/mobile.apk")
        asset.add("mirrors", mirrors)
        assertEquals(2, select(root.toString())!!.urls.size)
        mirrors.add("https://evil.example/malware.apk")
        assertThrows(IllegalArgumentException::class.java) { select(root.toString()) }
        assertEquals(1, select()!!.urls.size)
    }
}
