package com.wuming.tv.core.media

/** Resolves one manifest source into a final player target. */
interface SourceResolver {
    fun adapterId(): String
    fun resolve(source: MediaSourceDescriptor): PlaybackTarget
}
