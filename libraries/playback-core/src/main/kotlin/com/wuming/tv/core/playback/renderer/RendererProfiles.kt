package com.wuming.tv.core.playback.renderer

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.security.MessageDigest

/** Evidence describes the renderer, never the TVBox application or media URLs. */
enum class RendererCapability { MP4, HLS, DASH, H264, HEVC, AAC, SEEK, BYTE_RANGES, CHUNKED, STOP, RECAST }
enum class CapabilityState { UNKNOWN, SUPPORTED, UNSUPPORTED }
enum class EvidenceResult { PROGRESS, CONTROLLED_SUCCESS, CONTROLLED_FAILURE, INCONCLUSIVE }
data class RendererIdentity(val deviceKey: String, val name: String, val manufacturer: String,
    val model: String, val firmware: String, val services: String) {
    fun profileKey(): String = key("$deviceKey|$manufacturer|$model|$firmware|$services")
    companion object {
        @JvmStatic fun key(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
data class RendererEvidence(val capability: RendererCapability, val result: EvidenceResult,
    val session: String, val timestamp: Long, val appVersion: String, val reason: String,
    val httpCode: Int = -1, val upnpCode: Int = -1)
data class RendererProfile(val identity: RendererIdentity,
    val evidence: MutableList<RendererEvidence> = mutableListOf(),
    val overrides: MutableMap<RendererCapability, CapabilityState> = linkedMapOf())
data class BundledRendererRule(val manufacturer: String, val model: String, val firmware: String,
    val capability: RendererCapability, val state: CapabilityState, val evidenceReference: String)
interface RendererProfileStore { fun read(): String?; fun write(json: String) }

/** Shared policy; ports own storage location and durability. Invalid/newer files are never overwritten. */
class RendererProfiles(private val store: RendererProfileStore,
    private val rules: List<BundledRendererRule> = RendererProfileRules.bundled()) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val profiles = linkedMapOf<String, RendererProfile>()
    init {
        store.read()?.let { text ->
            require(text.length <= 2_000_000) { "Renderer config too large" }
            val root = JsonParser.parseString(text).asJsonObject
            require(root.get("schemaVersion").asInt == 1) { "Unsupported renderer config version" }
            val entries = root.getAsJsonArray("profiles")
            require(entries.size() <= 128)
            for (entry in entries) {
                val p = gson.fromJson(entry, RendererProfile::class.java)
                validate(p)
                require(profiles.put(p.identity.profileKey(), p) == null) { "Duplicate renderer profile" }
            }
        }
    }
    private fun validate(p: RendererProfile) {
        require(p.identity.deviceKey.matches(Regex("[a-f0-9]{64}")))
        require(listOf(p.identity.name, p.identity.manufacturer, p.identity.model,
            p.identity.firmware, p.identity.services).all { it.length <= 512 })
        require(p.evidence.size <= 128)
        for (e in p.evidence) {
            require(e.capability in RendererCapability.entries && e.result in EvidenceResult.entries)
            require(e.session.length in 1..128 && e.timestamp >= 0 && e.appVersion.length <= 128)
            require(e.reason.matches(Regex("[A-Za-z0-9_.-]{1,80}")))
        }
        require(p.overrides.all { (k,v) -> k in RendererCapability.entries && v in CapabilityState.entries })
    }
    /** Repair identities produced by the Android AVTransport-only discovery filter. */
    @Synchronized fun upgradeFilteredServices(identity: RendererIdentity) {
        val prefix = "urn:schemas-upnp-org:service:"
        if (identity.services.split(',').toSet() != setOf("${prefix}AVTransport:1",
                "${prefix}ConnectionManager:1", "${prefix}RenderingControl:1")) return
        val legacy = identity.copy(services = "${prefix}AVTransport:1")
        val previous = profiles[legacy.profileKey()] ?: return
        val copy = snapshot()
        val current = copy[identity.profileKey()]
        val evidence = (previous.evidence.filter { it.reason != "dlna_advertised" } + current?.evidence.orEmpty())
            .distinctBy { Triple(it.session, it.capability, it.result) }.sortedBy { it.timestamp }.takeLast(128)
        val overrides = previous.overrides.toMutableMap().apply { putAll(current?.overrides.orEmpty()) }
        copy.remove(legacy.profileKey())
        copy[identity.profileKey()] = RendererProfile(identity, evidence.toMutableList(), overrides)
        commit(copy)
    }
    @Synchronized fun identify(identity: RendererIdentity) {
        val id = identity.profileKey()
        if (profiles[id]?.identity == identity) return
        val copy = snapshot()
        if (id !in copy && copy.size >= 128) throw IllegalStateException("Renderer profile capacity reached")
        copy[id] = RendererProfile(identity, copy[id]?.evidence ?: mutableListOf(), copy[id]?.overrides ?: linkedMapOf())
        commit(copy)
    }
    @Synchronized fun record(identity: RendererIdentity, evidence: RendererEvidence) {
        val copy = snapshot()
        val p = copy.getOrPut(identity.profileKey()) { RendererProfile(identity) }
        if (p.evidence.any { it.session == evidence.session && it.capability == evidence.capability && it.result == evidence.result }) return
        p.evidence.add(evidence)
        while (p.evidence.size > 128) p.evidence.removeAt(0)
        commit(copy)
    }
    @Synchronized fun advertised(identity: RendererIdentity, capabilities: Set<RendererCapability>, version: String) {
        replaceAdvertisements(identity, capabilities, RendererCapability.entries.toSet(), version)
    }
    /** A failed format query must not discard independently read actions or saved format declarations. */
    @Synchronized fun advertisedActions(identity: RendererIdentity, actions: Set<String>, version: String) {
        replaceAdvertisements(identity, RendererAdvertisements.parse("", actions),
            setOf(RendererCapability.SEEK, RendererCapability.STOP), version)
    }
    private fun replaceAdvertisements(identity: RendererIdentity, capabilities: Set<RendererCapability>,
        scope: Set<RendererCapability>, version: String) {
        val copy = snapshot()
        val p = copy.getOrPut(identity.profileKey()) { RendererProfile(identity) }
        p.evidence.removeAll { it.reason == "dlna_advertised" && it.capability in scope }
        capabilities.forEach { p.evidence.add(RendererEvidence(it, EvidenceResult.INCONCLUSIVE,
            "dlna-description", System.currentTimeMillis(), version.take(128), "dlna_advertised")) }
        while (p.evidence.size > 128) p.evidence.removeAt(0)
        commit(copy)
    }
    @Synchronized fun state(identity: RendererIdentity, capability: RendererCapability): CapabilityState {
        return resolve(identity, capability, profiles[identity.profileKey()])
    }
    private fun resolve(identity: RendererIdentity, capability: RendererCapability, p: RendererProfile?): CapabilityState {
        p?.overrides?.get(capability)?.let { return it }
        val evidence = p?.evidence?.filter { it.capability == capability } ?: emptyList()
        val good = evidence.filter { it.result == EvidenceResult.CONTROLLED_SUCCESS || it.result == EvidenceResult.PROGRESS }
        val bad = evidence.filter { it.result == EvidenceResult.CONTROLLED_FAILURE }
        if (good.isNotEmpty() && bad.isNotEmpty()) return CapabilityState.UNKNOWN
        if (good.any { it.result == EvidenceResult.CONTROLLED_SUCCESS } || good.map { it.session }.distinct().size >= 2)
            return CapabilityState.SUPPORTED
        if (bad.map { it.session }.distinct().size >= 2) return CapabilityState.UNSUPPORTED
        if (good.isNotEmpty() || bad.isNotEmpty()) return CapabilityState.UNKNOWN
        // Reviewed exact-firmware rules only; unknown firmware cannot match a global rule.
        return rules.firstOrNull { identity.firmware != "unknown" && it.firmware == identity.firmware &&
            it.manufacturer == identity.manufacturer && it.model == identity.model &&
            it.capability == capability && it.evidenceReference.isNotBlank() }?.state ?: CapabilityState.UNKNOWN
    }
    @Synchronized fun override(identity: RendererIdentity, capability: RendererCapability, state: CapabilityState?) {
        val copy = snapshot()
        val p = copy.getOrPut(identity.profileKey()) { RendererProfile(identity) }
        if (state == null) p.overrides.remove(capability) else p.overrides[capability] = state
        commit(copy)
    }
    @Synchronized fun reset(deviceKey: String) { commit(snapshot().filterValues { it.identity.deviceKey != deviceKey }.toMutableMap()) }
    @Synchronized fun identities(): List<RendererIdentity> = profiles.values.map { it.identity }

    @Synchronized fun export(identity: RendererIdentity): String {
        val p = profiles[identity.profileKey()] ?: RendererProfile(identity)
        // Export is reviewable evidence, not an automatic rule submission. No name/device ID/session IDs.
        return gson.toJson(mapOf("schemaVersion" to 1, "manufacturer" to identity.manufacturer,
            "model" to identity.model, "firmware" to identity.firmware, "services" to identity.services,
            "capabilities" to RendererCapability.entries.associateWith { state(identity, it) },
            "assessments" to RendererCapability.entries.associateWith { assessment(p, it) },
            "overrides" to p.overrides, "evidence" to p.evidence.map { mapOf("capability" to it.capability,
                "result" to it.result, "timestamp" to it.timestamp, "appVersion" to it.appVersion,
                "reason" to it.reason, "httpCode" to it.httpCode, "upnpCode" to it.upnpCode) }))
    }
    private fun assessment(p: RendererProfile, capability: RendererCapability): Map<String, Any> {
        val observations = p.evidence.filter { it.capability == capability }
        val state = resolve(p.identity, capability, p)
        val confidence = when {
            capability in p.overrides -> "manual_override"
            state == CapabilityState.UNKNOWN -> "insufficient_or_conflicting"
            observations.none { it.result != EvidenceResult.INCONCLUSIVE } -> "reviewed_rule"
            observations.any { it.result == EvidenceResult.CONTROLLED_SUCCESS || it.result == EvidenceResult.CONTROLLED_FAILURE } -> "controlled_test"
            else -> "observed_playhead_progress"
        }
        return mapOf("state" to state, "confidence" to confidence,
            "unknownReason" to when {
                state != CapabilityState.UNKNOWN -> ""
                capability in p.overrides -> "manual_override"
                observations.any { it.result == EvidenceResult.CONTROLLED_FAILURE } &&
                    observations.any { it.result == EvidenceResult.CONTROLLED_SUCCESS || it.result == EvidenceResult.PROGRESS } -> "conflicting_evidence"
                observations.any { it.reason != "dlna_advertised" } -> "insufficient_evidence"
                else -> "not_tested"
            },
            "advertised" to observations.any { it.reason == "dlna_advertised" },
            "independentSessions" to observations.map { it.session }.distinct().size,
            "lastObservedAt" to (observations.maxOfOrNull { it.timestamp } ?: 0L))
    }

    private fun snapshot() = profiles.mapValues { (_,p) -> p.copy(evidence = p.evidence.toMutableList(), overrides = p.overrides.toMutableMap()) }.toMutableMap()
    private fun commit(copy: MutableMap<String, RendererProfile>) {
        require(copy.size <= 128)
        copy.values.forEach(::validate)
        store.write(gson.toJson(mapOf("schemaVersion" to 1, "profiles" to copy.values.map { p ->
            mapOf("identity" to p.identity, "evidence" to p.evidence, "overrides" to p.overrides,
                "assessments" to RendererCapability.entries.associateWith { assessment(p, it) })
        })))
        profiles.clear(); profiles.putAll(copy)
    }
}
