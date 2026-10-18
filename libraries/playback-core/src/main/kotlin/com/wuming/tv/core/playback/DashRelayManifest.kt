package com.wuming.tv.core.playback

import java.io.StringReader
import java.io.StringWriter
import java.net.URI
import java.math.BigDecimal
import java.math.BigInteger
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** Relay file-backed DASH; provider compatibility prefers AVC and repairs metadata without changing byte offsets. */
object DashRelayManifest {
    data class Result(val manifest: String, val rewrittenUrls: Int, val removedVideoRepresentations: Int = 0,
                      val normalizedMetadataFields: Int = 0)
    @JvmStatic fun rewrite(manifest: String, source: String, relay: java.util.function.Function<String, String>): Result {
        return rewrite(manifest, source, relay, false)
    }
    @JvmStatic fun rewrite(manifest: String, source: String, relay: java.util.function.Function<String, String>, preferAvc: Boolean): Result {
        require(manifest.length <= 2_000_000 && !manifest.contains("<!DOCTYPE", true) && !manifest.contains("<!ENTITY", true))
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw IllegalArgumentException("External XML entities are not allowed") }
        }
        val document = builder.parse(InputSource(StringReader(manifest)))
        require(document.documentElement.localName == "MPD")
        var removed = 0
        val normalized = if (preferAvc) normalizeProviderMetadata(document.documentElement) else 0
        if (preferAvc) {
            val periods = document.getElementsByTagNameNS("*", "Period")
            for (i in 0 until periods.length) {
                val period = periods.item(i) as Element
                val nodes = period.getElementsByTagNameNS("*", "Representation")
                val representations = (0 until nodes.length).map { nodes.item(it) as Element }
                fun codec(rep: Element): String = rep.getAttribute("codecs").ifEmpty {
                    (rep.parentNode as? Element)?.getAttribute("codecs").orEmpty()
                }.lowercase()
                // REGRESSION (Coocaa internal DLNA, 2026-09-28): the receiver selected HEVC,
                // fetched its init/index, then failed; the AVC alternative played successfully.
                // Bilibili puts codecs in SEPARATE AdaptationSets. Filtering within one set
                // removes nothing. Limit the AVC alternative check to this Period and retain
                // HEVC/AV1 when no AVC alternative exists; this is not proof they never work.
                // See prefersAvcAcrossBilibiliAdaptationSetsButNotAcrossPeriods in the test.
                if (representations.any { codec(it).startsWith("avc1") || codec(it).startsWith("avc3") }) {
                    for (rep in representations) {
                        if (codec(rep).let { it.startsWith("hvc1") || it.startsWith("hev1") || it.startsWith("av01") }) {
                            val adaptation = rep.parentNode
                            adaptation.removeChild(rep)
                            removed++
                            if (adaptation is Element && adaptation.localName == "AdaptationSet" &&
                                adaptation.getElementsByTagNameNS("*", "Representation").length == 0) {
                                adaptation.parentNode.removeChild(adaptation)
                            }
                        }
                    }
                }
            }
        }
        var count = 0
        fun children(element: Element): List<Element> = (0 until element.childNodes.length)
            .mapNotNull { element.childNodes.item(it) as? Element }
        fun walk(element: Element, inherited: URI) {
            val entries = children(element)
            val bases = entries.filter { it.localName == "BaseURL" }
            val base = bases.firstOrNull()?.textContent?.trim()?.let { inherited.resolve(it) } ?: inherited
            val fileRepresentation = element.localName == "Representation" &&
                entries.any { it.localName == "SegmentBase" } &&
                entries.none { it.localName == "SegmentTemplate" || it.localName == "SegmentList" }
            // External CDN BaseURLs need the relay too: a loopback-only rewrite leaves the
            // TV fetching unauthenticated media directly. Resolve XML-decoded URLs so signed
            // query separators stay intact, and preserve SegmentBase byte offsets exactly.
            // SegmentTemplate directory rewriting requires a different contract; do not
            // treat directories as complete media files (DashRelayManifestTest covers this).
            if (fileRepresentation) {
                for (node in bases) {
                    val target = inherited.resolve(node.textContent.trim())
                    if (target.scheme in listOf("http", "https")) {
                        val replacement = relay.apply(target.toString())
                        if (replacement != target.toString()) { node.textContent = replacement; count++ }
                    }
                }
            }
            for (node in entries) if (node.localName != "BaseURL") walk(node, base)
        }
        walk(document.documentElement, URI(source))
        if (count == 0 && removed == 0 && normalized == 0) return Result(manifest, 0)
        val writer = StringWriter()
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(writer))
        return Result(writer.toString(), count, removed, normalized)
    }

    private fun normalizeProviderMetadata(root: Element): Int {
        var changed = 0
        val nodes = root.getElementsByTagNameNS("*", "*")
        // Snapshot before inserting AudioChannelConfiguration elements into this live NodeList.
        val elements = (0 until nodes.length).map { nodes.item(it) as Element }
        for (element in elements) {
            if (element.namespaceURI != root.namespaceURI) continue
            if (element.localName !in listOf("Representation", "AdaptationSet")) continue
            val frameRate = element.getAttribute("frameRate")
            // DASH FrameRateType accepts integers or fractions, not decimal frame rates.
            // Preserve the provider's exact value rather than rounding 29.992 to 30.
            if (frameRate.matches(Regex("[0-9]{1,10}\\.[0-9]{1,9}"))) {
                val decimal = BigDecimal(frameRate).stripTrailingZeros()
                val denominator = BigInteger.TEN.pow(maxOf(0, decimal.scale()))
                val numerator = decimal.multiply(BigDecimal(denominator)).toBigIntegerExact()
                val divisor = numerator.gcd(denominator)
                val reducedDenominator = denominator / divisor
                element.setAttribute("frameRate", if (reducedDenominator == BigInteger.ONE)
                    (numerator / divisor).toString() else "${numerator / divisor}/$reducedDenominator")
                changed++
            }
            val mime = element.getAttribute("mimeType").ifEmpty {
                (element.parentNode as? Element)?.getAttribute("mimeType").orEmpty()
            }
            if (!mime.startsWith("audio/")) continue
            // The provider writes bitrate-like numbers (e.g. 192000) as sampleRate although
            // the measured AAC init is 44100 Hz. This is not a DASH attribute: omit it, never
            // relabel the untrusted number as audioSamplingRate or invent a fixed sample rate.
            if (element.hasAttribute("sampleRate")) {
                element.removeAttribute("sampleRate")
                changed++
            }
            if (element.hasAttribute("numChannels")) {
                val channels = element.getAttribute("numChannels").toIntOrNull()
                element.removeAttribute("numChannels")
                changed++
                // Mono/stereo counts map directly; multichannel layouts need init metadata.
                if (channels in 1..2 &&
                    element.getElementsByTagNameNS("*", "AudioChannelConfiguration").length == 0) {
                    val descriptor = element.ownerDocument.createElementNS(element.namespaceURI, "AudioChannelConfiguration")
                    descriptor.setAttribute("schemeIdUri", "urn:mpeg:dash:23003:3:audio_channel_configuration:2011")
                    descriptor.setAttribute("value", channels.toString())
                    // RepresentationBase descriptors precede BaseURL/SegmentBase. FramePacking
                    // descriptors, if present, precede AudioChannelConfiguration in the schema.
                    val children = element.childNodes
                    val before = (0 until children.length).mapNotNull { children.item(it) as? Element }
                        .firstOrNull { it.localName != "FramePacking" }
                    element.insertBefore(descriptor, before)
                }
            }
        }
        return changed
    }
}
