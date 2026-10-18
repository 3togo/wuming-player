package com.wuming.tv.core.epg

import java.io.InputStream
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

data class XmlTvGuide(
    val channels: List<GuideChannel>,
    val programmes: List<ProgrammeGuideEntry>,
)

object XmlTvParser {

    private val localTime = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    private val offsetTime = DateTimeFormatter.ofPattern("yyyyMMddHHmmss xx")
    private val colonOffsetTime = DateTimeFormatter.ofPattern("yyyyMMddHHmmss xxx")

    fun parse(input: InputStream, defaultZone: ZoneId = ZoneId.systemDefault()): XmlTvGuide {
        val factory = XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty("javax.xml.stream.isSupportingExternalEntities", false)
        }
        val reader = factory.createXMLStreamReader(input)
        val channels = mutableListOf<GuideChannel>()
        val programmes = mutableListOf<ProgrammeGuideEntry>()
        var channelId = ""
        var channelNames = mutableListOf<String>()
        var programme: PendingProgramme? = null
        var textTarget: String? = null
        val text = StringBuilder()

        try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                        "channel" -> {
                            channelId = reader.getAttributeValue(null, "id").orEmpty()
                            channelNames = mutableListOf()
                        }
                        "programme" -> programme = PendingProgramme(
                            channelId = reader.getAttributeValue(null, "channel").orEmpty(),
                            start = reader.getAttributeValue(null, "start").orEmpty(),
                            stop = reader.getAttributeValue(null, "stop").orEmpty(),
                        )
                        "display-name", "title", "desc", "category" -> {
                            textTarget = reader.localName
                            text.setLength(0)
                        }
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA ->
                        if (textTarget != null) text.append(reader.text)
                    XMLStreamConstants.END_ELEMENT -> when (reader.localName) {
                        "display-name" -> {
                            text.toString().trim().takeIf(String::isNotEmpty)?.let(channelNames::add)
                            textTarget = null
                        }
                        "title" -> {
                            programme?.title = text.toString().trim()
                            textTarget = null
                        }
                        "desc" -> {
                            programme?.description = text.toString().trim()
                            textTarget = null
                        }
                        "category" -> {
                            programme?.category = text.toString().trim()
                            textTarget = null
                        }
                        "channel" -> if (channelId.isNotBlank()) {
                            channels += GuideChannel(
                                channelId,
                                channelNames.firstOrNull().orEmpty().ifBlank { channelId },
                                channelNames.drop(1).toSet(),
                            )
                        }
                        "programme" -> {
                            programme?.build(defaultZone)?.let(programmes::add)
                            programme = null
                        }
                    }
                }
            }
        } finally {
            reader.close()
        }
        return XmlTvGuide(channels.distinctBy(GuideChannel::id), programmes)
    }

    private val offsetPattern = Regex("\\d{14} [+-]\\d{4}")
    private val colonOffsetPattern = Regex("\\d{14} [+-]\\d{2}:\\d{2}")
    private val whitespacePattern = Regex("\\s+")

    private fun parseTime(value: String, zone: ZoneId): Long? {
        val normalized = value.trim().replace(whitespacePattern, " ")
        return runCatching {
            when {
                offsetPattern.matches(normalized) ->
                    OffsetDateTime.parse(normalized, offsetTime).toInstant().toEpochMilli()
                colonOffsetPattern.matches(normalized) ->
                    OffsetDateTime.parse(normalized, colonOffsetTime).toInstant().toEpochMilli()
                else -> LocalDateTime.parse(normalized.take(14), localTime)
                    .atZone(zone).toInstant().toEpochMilli()
            }
        }.getOrNull()
    }

    private data class PendingProgramme(
        val channelId: String,
        val start: String,
        val stop: String,
        var title: String = "",
        var description: String = "",
        var category: String = "",
    ) {
        fun build(zone: ZoneId): ProgrammeGuideEntry? {
            val startTime = parseTime(start, zone) ?: return null
            val endTime = parseTime(stop, zone) ?: return null
            if (channelId.isBlank() || title.isBlank() || endTime < startTime) return null
            return ProgrammeGuideEntry(channelId, title, startTime, endTime, description, category)
        }
    }
}
