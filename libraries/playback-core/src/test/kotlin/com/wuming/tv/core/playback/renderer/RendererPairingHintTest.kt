package com.wuming.tv.core.playback.renderer

import org.junit.Assert.*
import org.junit.Test

class RendererPairingHintTest {
    private val legacyId = "3f35ac2d-cbb2-3900-a78b-2380956508fa"

    @Test fun explicitMarkerRequiresAnActualDenial() {
        assertTrue(hint(403, "Forbidden", RendererPairingHint.MODEL_DESCRIPTION, "different-id"))
        for (status in listOf(-1, 200, 401, 500))
            assertFalse(hint(status, "Forbidden", RendererPairingHint.MODEL_DESCRIPTION, "different-id"))
    }

    @Test fun oldReceiverRequiresTheCompleteFingerprintAndPairingResponse() {
        assertTrue(hint(403, "QR pairing required", "DLNA Renderer", legacyId))
        assertTrue(hint(403, "QR pairing required", "DLNA Renderer", "uuid:$legacyId"))
        assertFalse(hint(403, "Forbidden", "DLNA Renderer", legacyId))
        assertFalse(hint(403, null, "DLNA Renderer", legacyId))
        assertFalse(hint(403, "QR pairing required", "Other renderer", legacyId))
        assertFalse(hint(403, "QR pairing required", "DLNA Renderer", "other-id"))
        assertFalse(RendererPairingHint.requiresQrPairing(403, "QR pairing required", "DLNA Renderer",
            "Skyworth-Coocaa", "Skyworth Media Renderer", legacyId))
    }

    @Test fun missingMetadataAndGenericErrorsDoNotOfferAppSpecificInstructions() {
        assertFalse(RendererPairingHint.requiresQrPairing(403, "Forbidden", null, null, null, null))
        assertFalse(RendererPairingHint.requiresQrPairing(403, "QR pairing required", "DLNA Renderer", null, null, legacyId))
        assertFalse(hint(-1, "QR pairing required", "DLNA Renderer", legacyId))
    }

    private fun hint(status: Int, reason: String?, description: String, id: String) =
        RendererPairingHint.requiresQrPairing(status, reason, description, "Skyworth", "7AA49_P3D", id)
}
