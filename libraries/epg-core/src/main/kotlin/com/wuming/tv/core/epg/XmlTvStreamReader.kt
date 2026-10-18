package com.wuming.tv.core.epg

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.XMLReader
import org.xml.sax.ext.DefaultHandler2
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.zip.GZIPInputStream
import javax.xml.parsers.SAXParserFactory

/** Android/JVM SAX reader. Callbacks are provisional until the entire pass succeeds. */
object XmlTvStreamReader {

    @JvmField
    val DEFAULT_LIMITS = Limits(256L * 1024 * 1024, 2_000_000, 65536, 32)

    data class Limits(val bytes: Long, val records: Int, val textCharacters: Int, val depth: Int) {
        init {
            if (bytes < 1 || records < 1 || textCharacters < 1 || depth < 1)
                throw IllegalArgumentException("XMLTV limits must be positive")
        }
    }

    data class Channel(val id: String, val names: List<String>, val icon: String) {
        @JvmName("id")
        fun idAccessor(): String = id
        @JvmName("names")
        fun namesAccessor(): List<String> = names
        @JvmName("icon")
        fun iconAccessor(): String = icon
    }
    data class Programme(val channel: String, val start: String, val stop: String, val title: String) {
        @JvmName("channel")
        fun channelAccessor(): String = channel
        @JvmName("start")
        fun startAccessor(): String = start
        @JvmName("stop")
        fun stopAccessor(): String = stop
        @JvmName("title")
        fun titleAccessor(): String = title
    }

    interface Visitor {
        fun readChannels(): Boolean = false
        @Throws(SAXException::class)
        fun channel(channel: Channel) {}
        @Throws(SAXException::class)
        fun selectProgramme(channel: String): Boolean = false
        @Throws(SAXException::class)
        fun programme(programme: Programme) {}
    }

    /** Opens a fresh pass, decoding gzip directly without an expanded temporary file. */
    @Throws(Exception::class)
    @JvmStatic
    fun read(file: File, visitor: Visitor) {
        BufferedInputStream(FileInputStream(file)).use { input ->
            input.mark(2)
            val gzip = input.read() == 0x1f && input.read() == 0x8b
            input.reset()
            if (gzip) {
                GZIPInputStream(input).use { expanded -> read(expanded, visitor, DEFAULT_LIMITS) }
            } else read(input, visitor, DEFAULT_LIMITS)
        }
    }

    /** Caller owns input. The byte budget applies after decompression, including skipped records. */
    @Throws(Exception::class)
    @JvmStatic
    fun read(input: InputStream, visitor: Visitor, limits: Limits) {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        val reader: XMLReader = factory.newSAXParser().xmlReader
        val handler = Handler(visitor, limits)
        reader.contentHandler = handler
        reader.errorHandler = handler
        reader.entityResolver = handler
        // Fail closed if the platform cannot install the DTD guard.
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        // Suppress automatic loading of external DTD subsets so that an external
        // DOCTYPE declaration is accepted without fetching the referenced file.
        // Android ExpatReader does not support this feature and ignores it safely.
        try { reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        catch (e: Exception) { /* Feature not available on this platform */ }
        val bounded = BoundedInput(input, limits.bytes)
        reader.parse(InputSource(bounded))
        // Some SAX implementations finish at the XML document boundary without surfacing
        // a gzip trailer failure. Require the underlying stream to reach a verified EOF.
        val tail = ByteArray(8192)
        while (bounded.read(tail) != -1) { /* Byte budget and cancellation still apply. */ }
    }

    private class BoundedInput(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
        @Throws(IOException::class)
        private fun check(count: Int) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("XMLTV cancelled")
            if (count > 0) {
                remaining -= count
                if (remaining < 0) throw IOException("XMLTV byte limit exceeded")
            }
        }

        @Throws(IOException::class)
        override fun read(): Int {
            check(0)
            val value = `in`.read()
            check(if (value < 0) 0 else 1)
            return value
        }

        @Throws(IOException::class)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            check(0)
            val count = `in`.read(buffer, offset, if (remaining >= length) length else remaining.toInt() + 1)
            check(count)
            return count
        }

        override fun close() { /* Caller owns input. */ }
    }

    private val PREDEFINED_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos")

    private class Handler(private val visitor: Visitor, private val limits: Limits) : DefaultHandler2() {
        private var depth = 0
        private var records = 0
        private var kind = ""
        private var id = ""
        private var start = ""
        private var stop = ""
        private var title = ""
        private var icon = ""
        private var field = ""
        private var selected = false
        private var names: MutableList<String>? = null
        private var dtdSystemId: String? = null
        private val text = StringBuilder()

        @Throws(SAXException::class)
        private fun check() {
            if (Thread.currentThread().isInterrupted) throw SAXException(InterruptedIOException("XMLTV cancelled"))
        }

        private fun attribute(attributes: Attributes, name: String): String {
            val value = attributes.getValue(name)
            return value ?: ""
        }

        @Throws(SAXException::class)
        override fun startElement(uri: String?, local: String, qName: String?, attributes: Attributes) {
            check()
            if (++depth > limits.depth) throw SAXException("XMLTV depth limit exceeded")
            for (i in 0 until attributes.length)
                if (attributes.getValue(i).length > limits.textCharacters) throw SAXException("XMLTV attribute limit exceeded")
            if (depth == 1 && local != "tv") throw SAXException("Expected XMLTV tv root")
            if (depth == 2 && (local == "channel" || local == "programme")) {
                if (++records > limits.records) throw SAXException("XMLTV record limit exceeded")
                kind = local
                selected = if (local == "channel") visitor.readChannels() else visitor.selectProgramme(attribute(attributes, "channel"))
                if (selected) {
                    id = attribute(attributes, if (local == "channel") "id" else "channel")
                    start = attribute(attributes, "start")
                    stop = attribute(attributes, "stop")
                    title = ""
                    icon = ""
                    names = if (local == "channel") mutableListOf() else null
                }
            }
            if (depth == 3 && selected) {
                if ((kind == "channel" && local == "display-name") ||
                    (kind == "programme" && local == "title")
                ) {
                    field = local
                    text.setLength(0)
                } else if (kind == "channel" && local == "icon") icon = attribute(attributes, "src")
            }
        }

        @Throws(SAXException::class)
        override fun characters(chars: CharArray, offset: Int, count: Int) {
            check()
            if (field.isNotEmpty()) {
                if (text.length + count > limits.textCharacters) throw SAXException("XMLTV text limit exceeded")
                text.append(chars, offset, count)
            }
        }

        @Throws(SAXException::class)
        override fun endElement(uri: String?, local: String, qName: String?) {
            check()
            if (depth == 3 && field.isNotEmpty() && local == field) {
                if (field == "title") title = text.toString()
                else {
                    if (names!!.size >= 256) throw SAXException("XMLTV alias limit exceeded")
                    names!!.add(text.toString())
                }
                field = ""
            }
            if (depth == 2) {
                if (selected && kind == "channel") visitor.channel(Channel(id, names!!.toList(), icon))
                if (selected && kind == "programme") visitor.programme(Programme(id, start, stop, title))
                selected = false
                names = null
                kind = ""
            }
            depth--
        }

        @Throws(SAXException::class)
        override fun startDTD(name: String?, publicId: String?, systemId: String?) {
            if (systemId == null && publicId == null) {
                throw SAXException("XMLTV internal DTDs are not supported")
            }
            dtdSystemId = systemId
        }

        @Throws(SAXException::class)
        override fun endDTD() {
            dtdSystemId = null
        }

        @Throws(SAXException::class)
        override fun startEntity(name: String?) {
            if (name != null && name !in PREDEFINED_ENTITIES) {
                throw SAXException("XMLTV entity references are not supported: $name")
            }
        }

        @Throws(SAXException::class)
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource {
            if (systemId != null && systemId == dtdSystemId) {
                val source = InputSource(ByteArrayInputStream(ByteArray(0)))
                source.systemId = systemId
                return source
            }
            throw SAXException("XMLTV external entities are not supported")
        }

        @Throws(SAXException::class)
        override fun error(error: SAXParseException) {
            throw error
        }

        @Throws(SAXException::class)
        override fun fatalError(error: SAXParseException) {
            throw error
        }
    }
}
