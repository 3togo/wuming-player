package com.wuming.tv.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class LegacyOpenDriveIdTest {

    @Test
    fun legacyIdsRemainRecognizableDuringMigrationWindow() {
        val item = "folder/movie 01.mp4"
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(item.toByteArray(StandardCharsets.UTF_8))

        val parsed = LegacyOpenDriveId.parse("opendrive-file:quark:$encoded")

        assertEquals(LegacyOpenDriveId.Kind.FILE, parsed.kind)
        assertEquals("quark", parsed.providerId)
        assertEquals(item, parsed.itemId)
        assertTrue(LegacyOpenDriveId.isLegacy("opendrive-file:quark:$encoded"))
    }

    @Test
    fun malformedLegacyIdsAreRejectedWithoutFallback() {
        assertFalse(LegacyOpenDriveId.isLegacy("opendrive-file:quark:not-base64!"))
        assertThrows(IllegalArgumentException::class.java) {
            LegacyOpenDriveId.parse("opendrive-file:Quark:YWJj")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LegacyOpenDriveId.parse("https://example.test/movie.mp4")
        }
    }
}
