package com.wuming.tv.core.media.subtitle

/** Pluggable subtitle catalog. Providers own their API or HTML parsing details. */
interface SubtitleProvider {
    fun id(): String

    @Throws(Exception::class)
    fun search(query: SubtitleQuery): SubtitlePage

    @Throws(Exception::class)
    // Keep the JVM entry nullable so adapters can preserve their own null-rejection contract.
    fun resolve(candidate: SubtitleCandidate?): SubtitleCandidate = candidate!!
}
