package com.wuming.tv.desktop.provenance

import com.wuming.tv.core.base.Digests
import org.junit.Assert.*
import org.junit.Test

class JnaDistributionProvenanceTest {
    @Test fun resolvedJarAndPackagedNoticesMatchTheReviewedPublisherBytes() {
        // Inspect the real runtime artifact without initializing JNA or loading native code.
        val jna = Class.forName("com.sun.jna.Native", false, javaClass.classLoader)
        assertEquals("Reviewed JNA 5.18.1 runtime artifact",
            "260c4b1e22b1db9e110ee441c4f13ce115f841fa48c41d78750986214b395557",
            Digests.sha256Hex(jna.protectionDomain.codeSource.location.openStream()))
        val notices = mapOf(
            "LICENSE" to "07c938b23950ab7d47a24ef35f9f5da3a05ae164278dc959ad6994135ed59ff1",
            "Apache-2.0.txt" to "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30",
            "NOTICE.txt" to "0fece64a09bc48b537c9e5eebb6beb072c71a32d1f8422efd82727cc4b5fd1b5",
        )
        for ((name, expected) in notices) {
            val path = "/third-party/jna/$name"
            val stream = checkNotNull(javaClass.getResourceAsStream(path)) { "Missing packaged notice $path" }
            assertEquals(path, expected, Digests.sha256Hex(stream))
        }
    }
}
