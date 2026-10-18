package com.wuming.tv.core.source

import com.google.gson.JsonObject
import java.util.function.Function

/** Whether a config section had anything to read. A missing section is a fact, not a silence. */
enum class SectionOutcome {
    /** An array (or a lone object) with at least one record. */
    PRESENT,

    /** The section was there and empty. */
    EMPTY,

    /** The document has no such key. */
    MISSING,

    /** The key exists but is not a list -- a typo'd or hand-edited section. */
    NOT_AN_ARRAY,
}

/** What happened to one record. Every record read gets exactly one of these. */
enum class RecordOutcome {
    KEPT,

    /** An earlier record already claimed this key; the first one wins, as everywhere else. */
    DUPLICATE,

    /** The record has no dedup key, so it cannot be indexed, selected or remembered. */
    BLANK_KEY,

    /** Nothing to call. */
    BLANK_API,

    /** Nothing to fetch or play. */
    BLANK_URL,

    /** The element was a string/number/null where an object was required. */
    NOT_AN_OBJECT,

    /** Reading the record's own fields threw. */
    MALFORMED,
}

/** One record as it passed the gate, with its position in the document. */
data class RecordVerdict<T>(val position: Int, val identifier: String, val outcome: RecordOutcome, val record: T?)

/**
 * The verdict of one whole section: what was read, what survived and why the rest did not.
 *
 * [verdicts] holds one entry per record read -- including the kept ones -- so a caller can always
 * account for the full input instead of only for whatever was dropped.
 */
class SectionAdmission<T> internal constructor(
    val name: String,
    val outcome: SectionOutcome,
    val verdicts: List<RecordVerdict<T>>,
) {
    val kept: List<T> = verdicts.mapNotNull { if (it.outcome == RecordOutcome.KEPT) it.record else null }

    val rejected: List<RecordVerdict<T>> = verdicts.filter { it.outcome != RecordOutcome.KEPT }

    /** Whether a caller should raise this section above routine logging: records were refused, or a section was unreadable. */
    val noteworthy: Boolean get() = rejected.isNotEmpty() || outcome == SectionOutcome.NOT_AN_ARRAY

    /**
     * A single log line that is a superset of the section: the section's own state, the record
     * count, and a count per outcome. Kept records are counted too, because "60 of 62" is only
     * readable when both halves are present.
     */
    fun summary(): String = buildString {
        append(name)
        append(" section=").append(outcome)
        append(" records=").append(verdicts.size)
        if (verdicts.isNotEmpty()) {
            append(" verdicts=").append(verdicts.groupingBy { it.outcome }.eachCount().toSortedMap().entries.joinToString(", ", "[", "]") { "${it.key}=${it.value}" })
        }
        if (rejected.isNotEmpty()) {
            append(" rejected=").append(rejected.size)
            append(' ').append(rejected.take(MAX_LISTED).joinToString(", ", "[", "]") { "#${it.position} ${it.outcome}(${it.identifier})" })
            if (rejected.size > MAX_LISTED) append("+${rejected.size - MAX_LISTED} more")
        }
    }

    private companion object {
        /** The histogram above already carries the total, so the enumeration stays bounded. */
        const val MAX_LISTED = 10
    }
}

/**
 * Keep/reject/dedup rules for one kind of config record.
 *
 * A TVBox config lists its sources in several parallel ways -- sites, lives, parses -- and each one
 * used to be deduplicated by whatever the surrounding code happened to compare: the site key here,
 * the whole object there. Two entries with the same key and different other fields then produced a
 * list and an index that disagreed with each other. This class owns the comparison once: the first
 * record for a key wins, unusable records are refused for a stated reason, and every record read
 * leaves a verdict behind.
 */
class RecordAdmission<T> private constructor(
    private val identify: Function<T, String>,
    private val rejection: Function<T, RecordOutcome?>?,
) {

    /** Admits records the caller already decoded. */
    @JvmOverloads
    fun admit(name: String, records: List<T>?, outcome: SectionOutcome = SectionOutcome.PRESENT): SectionAdmission<T> {
        val items = records?.mapIndexed { position, record -> position to record }.orEmpty()
        return SectionAdmission(name, outcomeOf(outcome, items), gate(items))
    }

    /**
     * Admits the [key] section of a TVBox document straight from its JSON.
     *
     * [decode] only ever sees objects; elements that are not objects, and decodes that throw, become
     * verdicts instead of exceptions or silent holes.
     */
    fun admitJson(root: JsonObject, key: String, decode: Function<JsonObject, T>): SectionAdmission<T> {
        val section = root.get(key)
        val elements = when {
            section == null || section.isJsonNull -> return SectionAdmission(key, SectionOutcome.MISSING, emptyList())
            section.isJsonObject -> listOf(section)
            section.isJsonArray -> section.asJsonArray.toList()
            else -> return SectionAdmission(key, SectionOutcome.NOT_AN_ARRAY, emptyList())
        }
        val unreadable = ArrayList<RecordVerdict<T>>(elements.size)
        val decoded = ArrayList<Pair<Int, T>>(elements.size)
        elements.forEachIndexed { position, element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject
            if (obj == null) {
                unreadable += RecordVerdict(position, "", RecordOutcome.NOT_AN_OBJECT, null)
            } else {
                try {
                    decoded += position to decode.apply(obj)
                } catch (e: Exception) {
                    // One field of the wrong type must not lose the rest of the section. The exception
                    // text stays out of the verdict: it can carry the config's own URLs and tokens.
                    unreadable += RecordVerdict(position, "", RecordOutcome.MALFORMED, null)
                }
            }
        }
        // Document order, so a verdict can always be read back against the config it came from.
        return SectionAdmission(key, outcomeOf(SectionOutcome.PRESENT, elements), (unreadable + gate(decoded)).sortedBy { it.position })
    }

    /** A section that was there but held nothing reads as EMPTY, never as a silence. */
    private fun outcomeOf(declared: SectionOutcome, elements: List<*>): SectionOutcome =
        if (elements.isEmpty() && declared == SectionOutcome.PRESENT) SectionOutcome.EMPTY else declared

    private fun gate(numbered: List<Pair<Int, T>>): List<RecordVerdict<T>> {
        val claimed = HashSet<String>(numbered.size)
        return numbered.map { (position, record) ->
            val identifier = identify.apply(record).orEmpty().trim()
            val reason = when {
                identifier.isEmpty() -> RecordOutcome.BLANK_KEY
                else -> rejection?.apply(record) ?: if (!claimed.add(identifier)) RecordOutcome.DUPLICATE else null
            }
            RecordVerdict(position, identifier, reason ?: RecordOutcome.KEPT, record)
        }
    }

    companion object {

        /** Records are deduplicated by [identify]; a blank identifier is refused. */
        @JvmStatic
        fun <T> of(identify: Function<T, String>): RecordAdmission<T> = RecordAdmission(identify, null)

        /**
         * [rejection] may refuse an otherwise well-keyed record, and reports `null` when the record
         * is usable.
         */
        @JvmStatic
        fun <T> of(identify: Function<T, String>, rejection: Function<T, RecordOutcome?>): RecordAdmission<T> =
            RecordAdmission(identify, rejection)
    }
}

/** Field rules shared by every platform for the record shapes a TVBox config can hold. */
object TvBoxRecordFields {

    /**
     * A site needs a key to be indexed and an api to be called. Its name stays out of the rules on
     * purpose: both clients fall back to the key when a config leaves it out, and a source that
     * works is not dropped over a missing caption.
     */
    @JvmStatic
    fun siteRejection(key: String, api: String): RecordOutcome? = when {
        api.isBlank() -> RecordOutcome.BLANK_API
        key.isBlank() -> RecordOutcome.BLANK_KEY
        else -> null
    }

    /** A live source is named and fetched by its url, so a config has to say which. */
    @JvmStatic
    fun liveRejection(url: String): RecordOutcome? = if (url.isBlank()) RecordOutcome.BLANK_URL else null
}
