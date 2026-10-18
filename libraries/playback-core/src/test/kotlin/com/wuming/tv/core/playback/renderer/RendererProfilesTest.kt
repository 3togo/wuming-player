package com.wuming.tv.core.playback.renderer

import org.junit.Assert.*
import org.junit.Test

class RendererProfilesTest {
    private class Memory : RendererProfileStore {
        var json: String? = null
        var fail = false
        override fun read() = json
        override fun write(json: String) { if (fail) throw java.io.IOException(); this.json = json }
    }
    private val tv = RendererIdentity(RendererIdentity.key("private-udn"), "Living room", "Vendor", "Model", "unknown", "AVTransport:1")
    private fun evidence(id: String, result: EvidenceResult) = RendererEvidence(RendererCapability.DASH, result, id, 100, "1", "test")
    private val filtered = tv.copy(services = "urn:schemas-upnp-org:service:AVTransport:1")
    private val complete = filtered.copy(services = listOf("AVTransport", "ConnectionManager", "RenderingControl")
        .joinToString(",") { "urn:schemas-upnp-org:service:$it:1" })
    @Test fun repairedDiscoveryPreservesConfirmedEvidenceAndOverridesAcrossReload() {
        val store = Memory(); val db = RendererProfiles(store)
        db.record(filtered, evidence("confirmed", EvidenceResult.CONTROLLED_SUCCESS))
        db.override(filtered, RendererCapability.HEVC, CapabilityState.UNSUPPORTED)
        db.advertised(filtered, setOf(RendererCapability.STOP), "old")
        db.upgradeFilteredServices(complete)
        val reopened = RendererProfiles(store)
        assertEquals(CapabilityState.SUPPORTED, reopened.state(complete, RendererCapability.DASH))
        assertEquals(CapabilityState.UNSUPPORTED, reopened.state(complete, RendererCapability.HEVC))
        assertEquals(listOf(complete), reopened.identities())
        assertFalse(reopened.export(complete).contains("dlna_advertised"))
        val saved = store.json
        reopened.upgradeFilteredServices(complete)
        assertEquals(saved, store.json)
    }
    @Test fun repairDoesNotShareEvidenceAcrossDevicesFirmwareOrOtherServiceChanges() {
        val db = RendererProfiles(Memory())
        db.record(filtered, evidence("confirmed", EvidenceResult.CONTROLLED_SUCCESS))
        for (other in listOf(complete.copy(deviceKey = RendererIdentity.key("other")),
                complete.copy(firmware = "new"), complete.copy(model = "different"),
                complete.copy(services = "urn:schemas-upnp-org:service:ConnectionManager:1"))) {
            db.upgradeFilteredServices(other)
            assertEquals(CapabilityState.UNKNOWN, db.state(other, RendererCapability.DASH))
        }
        assertEquals(listOf(filtered), db.identities())
    }
    @Test fun failedRepairLeavesOldProfileAndExistingNewOverridesWinOnRetry() {
        val store = Memory(); val db = RendererProfiles(store)
        db.record(filtered, evidence("confirmed", EvidenceResult.CONTROLLED_SUCCESS))
        db.override(filtered, RendererCapability.HEVC, CapabilityState.UNSUPPORTED)
        db.override(complete, RendererCapability.HEVC, CapabilityState.SUPPORTED)
        store.fail = true
        try { db.upgradeFilteredServices(complete); fail() } catch (_: java.io.IOException) {}
        assertEquals(2, db.identities().size)
        store.fail = false
        db.upgradeFilteredServices(complete)
        assertEquals(CapabilityState.SUPPORTED, db.state(complete, RendererCapability.HEVC))
        assertEquals(CapabilityState.SUPPORTED, db.state(complete, RendererCapability.DASH))
    }
    @Test fun shippedCatalogContainsOnlyReviewedExactRules() {
        RendererProfileRules.bundled().forEach {
            assertTrue(it.evidenceReference.isNotBlank())
            assertNotEquals("unknown", it.firmware)
        }
    }
    @Test fun transientFailuresAndPlayAcknowledgementsDoNotBanFormats() {
        val db = RendererProfiles(Memory())
        repeat(10) { db.record(tv, evidence("$it", EvidenceResult.INCONCLUSIVE)) }
        assertEquals(CapabilityState.UNKNOWN, db.state(tv, RendererCapability.DASH))
    }
    @Test fun independentEvidencePersistsButContradictionsBecomeUnknown() {
        val memory = Memory(); val db = RendererProfiles(memory)
        db.record(tv, evidence("a", EvidenceResult.PROGRESS))
        db.record(tv, evidence("a", EvidenceResult.PROGRESS))
        assertEquals(CapabilityState.UNKNOWN, db.state(tv, RendererCapability.DASH))
        db.record(tv, evidence("b", EvidenceResult.PROGRESS))
        assertEquals(CapabilityState.SUPPORTED, RendererProfiles(memory).state(tv, RendererCapability.DASH))
        db.record(tv, evidence("c", EvidenceResult.CONTROLLED_FAILURE))
        assertEquals(CapabilityState.UNKNOWN, db.state(tv, RendererCapability.DASH))
    }
    @Test fun controlledFailuresNeedIndependentRunsAndOverridesAreReversible() {
        val db = RendererProfiles(Memory())
        db.record(tv, evidence("a", EvidenceResult.CONTROLLED_FAILURE))
        assertEquals(CapabilityState.UNKNOWN, db.state(tv, RendererCapability.DASH))
        db.record(tv, evidence("b", EvidenceResult.CONTROLLED_FAILURE))
        assertEquals(CapabilityState.UNSUPPORTED, db.state(tv, RendererCapability.DASH))
        db.override(tv, RendererCapability.DASH, CapabilityState.SUPPORTED)
        assertEquals(CapabilityState.SUPPORTED, db.state(tv, RendererCapability.DASH))
        db.override(tv, RendererCapability.DASH, null)
        assertEquals(CapabilityState.UNSUPPORTED, db.state(tv, RendererCapability.DASH))
        db.reset(tv.deviceKey)
        assertEquals(CapabilityState.UNKNOWN, db.state(tv, RendererCapability.DASH))
    }
    @Test fun sameNameDifferentIdentityAndFirmwareDoNotShareEvidence() {
        val db = RendererProfiles(Memory())
        db.record(tv, evidence("a", EvidenceResult.CONTROLLED_SUCCESS))
        assertEquals(CapabilityState.UNKNOWN, db.state(tv.copy(deviceKey = RendererIdentity.key("other")), RendererCapability.DASH))
        assertEquals(CapabilityState.UNKNOWN, db.state(tv.copy(firmware = "2"), RendererCapability.DASH))
        assertEquals(CapabilityState.SUPPORTED, db.state(tv.copy(name = "Renamed"), RendererCapability.DASH))
    }
    @Test fun exportOmitsIdentityAndSessionsAndFailedSaveDoesNotMutateState() {
        val memory = Memory(); val db = RendererProfiles(memory)
        db.record(tv, evidence("private-session", EvidenceResult.CONTROLLED_SUCCESS))
        val report = db.export(tv)
        for (secret in listOf(tv.deviceKey, "Living room", "private-udn", "private-session")) assertFalse(report.contains(secret))
        memory.fail = true
        try { db.override(tv, RendererCapability.DASH, CapabilityState.UNSUPPORTED); fail() } catch (_: java.io.IOException) {}
        assertEquals(CapabilityState.SUPPORTED, db.state(tv, RendererCapability.DASH))
    }
    @Test fun corruptAndFutureConfigsArePreserved() {
        for (json in listOf("bad", "{\"schemaVersion\":2,\"profiles\":[]}", "{\"schemaVersion\":1,\"profiles\":[{}]}")) {
            val memory = Memory().apply { this.json = json }
            try { RendererProfiles(memory); fail() } catch (_: Exception) {}
            assertEquals(json, memory.json)
        }
    }
    @Test fun reviewedRulesRequireExactKnownFirmwareAndLocalEvidenceWins() {
        val known = tv.copy(firmware = "1")
        val db = RendererProfiles(Memory(), listOf(BundledRendererRule("Vendor", "Model", "1", RendererCapability.HLS, CapabilityState.UNSUPPORTED, "review-1")))
        assertEquals(CapabilityState.UNSUPPORTED, db.state(known, RendererCapability.HLS))
        assertEquals(CapabilityState.UNKNOWN, db.state(tv, RendererCapability.HLS))
        db.record(known, evidence("network", EvidenceResult.INCONCLUSIVE).copy(capability = RendererCapability.HLS))
        assertEquals(CapabilityState.UNSUPPORTED, db.state(known, RendererCapability.HLS))
        db.override(known, RendererCapability.HLS, CapabilityState.UNKNOWN)
        assertEquals(CapabilityState.UNKNOWN, db.state(known, RendererCapability.HLS))
    }
    @Test fun stalledPositionsAndSeeksDoNotTeachSupport() {
        val events = mutableListOf<RendererEvidence>()
        val observer = RendererPlaybackObservation(RendererCapability.DASH, "1", { events.add(it) })
        repeat(6) { observer.position(0, true) }; assertTrue(events.isEmpty())
        observer.seeking(); observer.position(120000, true); assertTrue(events.isEmpty())
        observer.position(123000, true); observer.position(126000, true); assertTrue(events.isEmpty())
        observer.position(129000, true); assertEquals(EvidenceResult.PROGRESS, events.single().result)
        observer.position(132000, true); assertEquals(1, events.size)
        observer.failure("SetAVTransportURI", -1, 501)
        assertEquals(EvidenceResult.INCONCLUSIVE, events.last().result)
    }
    @Test fun rememberedProfilesSurviveRestartWithoutLiveDiscovery() {
        val memory = Memory()
        RendererProfiles(memory).identify(tv)
        val offline = RendererProfiles(memory)
        assertEquals(listOf(tv), offline.identities())
        offline.override(offline.identities().single(), RendererCapability.AAC, CapabilityState.SUPPORTED)
        assertEquals(CapabilityState.SUPPORTED, RendererProfiles(memory).state(tv, RendererCapability.AAC))
        offline.reset(tv.deviceKey)
        assertTrue(RendererProfiles(memory).identities().isEmpty())
    }
    @Test fun evidenceIsBounded() {
        val memory = Memory(); val db = RendererProfiles(memory)
        repeat(200) { db.record(tv, evidence("$it", EvidenceResult.INCONCLUSIVE)) }
        val parsed = com.google.gson.JsonParser.parseString(memory.json).asJsonObject
        assertEquals(128, parsed.getAsJsonArray("profiles")[0].asJsonObject.getAsJsonArray("evidence").size())
    }
}
