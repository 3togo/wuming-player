package com.github.catvod.utils

import android.text.TextUtils
import androidx.annotation.Nullable
import kotlin.math.max

class UriUtil {
    companion object {
        private const val INDEX_COUNT = 4
        private const val SCHEME_COLON = 0
        private const val PATH = 1
        private const val QUERY = 2
        private const val FRAGMENT = 3

        @JvmStatic
        fun resolve(baseUri: String?, referenceUri: String?): String {
            var baseUri: String? = baseUri
            var referenceUri: String? = referenceUri
            val uri: StringBuilder = StringBuilder()
            baseUri = if (baseUri == null) "" else baseUri
            referenceUri = if (referenceUri == null) "" else referenceUri
            val refIndices: IntArray = getUriIndices(referenceUri)
            if (refIndices[SCHEME_COLON] != -1) {
                uri.append(referenceUri)
                removeDotSegments(uri, refIndices[PATH], refIndices[QUERY])
                return uri.toString()
            }
            val baseIndices: IntArray = getUriIndices(baseUri)
            if (refIndices[FRAGMENT] == 0) {
                return uri.append(baseUri, 0, baseIndices[FRAGMENT]).append(referenceUri).toString()
            }
            if (refIndices[QUERY] == 0) {
                return uri.append(baseUri, 0, baseIndices[QUERY]).append(referenceUri).toString()
            }
            if (refIndices[PATH] != 0) {
                val baseLimit: Int = baseIndices[SCHEME_COLON] + 1
                uri.append(baseUri, 0, baseLimit).append(referenceUri)
                return removeDotSegments(
                    uri,
                    baseLimit + refIndices[PATH],
                    baseLimit + refIndices[QUERY]
                )
            }
            if (referenceUri.get(refIndices[PATH]) == '/') {
                uri.append(baseUri, 0, baseIndices[PATH]).append(referenceUri)
                return removeDotSegments(
                    uri,
                    baseIndices[PATH],
                    baseIndices[PATH] + refIndices[QUERY]
                )
            }
            if (baseIndices[SCHEME_COLON] + 2 < baseIndices[PATH] &&
                baseIndices[PATH] == baseIndices[QUERY]
            ) {
                uri.append(baseUri, 0, baseIndices[PATH]).append('/').append(referenceUri)
                return removeDotSegments(
                    uri,
                    baseIndices[PATH],
                    baseIndices[PATH] + refIndices[QUERY] + 1
                )
            } else {
                val lastSlashIndex: Int = baseUri.lastIndexOf('/', baseIndices[QUERY] - 1)
                val baseLimit: Int = if (lastSlashIndex ==
                    -1
                ) {
                    baseIndices[PATH]
                } else {
                    lastSlashIndex + 1
                }
                uri.append(baseUri, 0, baseLimit).append(referenceUri)
                return removeDotSegments(uri, baseIndices[PATH], baseLimit + refIndices[QUERY])
            }
        }

        private fun removeDotSegments(uri: StringBuilder, offset: Int, limit: Int): String {
            var offset: Int = offset
            var limit: Int = limit
            if (offset >= limit) {
                return uri.toString()
            }
            if (uri.get(offset) == '/') {
                offset++
            }
            var segmentStart: Int = offset
            var i: Int = offset
            while (i <= limit) {
                val nextSegmentStart: Int
                if (i == limit) {
                    nextSegmentStart = i
                } else if (uri.get(i) == '/') {
                    nextSegmentStart = i + 1
                } else {
                    i++
                    continue
                }
                if (i == segmentStart + 1 && uri.get(segmentStart) == '.') {
                    uri.delete(segmentStart, nextSegmentStart)
                    limit -= nextSegmentStart - segmentStart
                    i = segmentStart
                } else if (i == segmentStart + 2 && uri.get(segmentStart) == '.' &&
                    uri.get(segmentStart + 1) == '.'
                ) {
                    val prevSegmentStart: Int = uri.lastIndexOf("/", segmentStart - 2) + 1
                    val removeFrom: Int = max(prevSegmentStart, offset)
                    uri.delete(removeFrom, nextSegmentStart)
                    limit -= nextSegmentStart - removeFrom
                    segmentStart = prevSegmentStart
                    i = prevSegmentStart
                } else {
                    i++
                    segmentStart = i
                }
            }
            return uri.toString()
        }

        private fun getUriIndices(uriString: String): IntArray {
            val indices: IntArray = IntArray(INDEX_COUNT)
            if (TextUtils.isEmpty(uriString)) {
                indices[SCHEME_COLON] = -1
                return indices
            }
            val length: Int = uriString.length
            var fragmentIndex: Int = uriString.indexOf('#')
            if (fragmentIndex == -1) {
                fragmentIndex = length
            }
            var queryIndex: Int = uriString.indexOf('?')
            if (queryIndex == -1 || queryIndex > fragmentIndex) {
                queryIndex = fragmentIndex
            }
            var schemeIndexLimit: Int = uriString.indexOf('/')
            if (schemeIndexLimit == -1 || schemeIndexLimit > queryIndex) {
                schemeIndexLimit = queryIndex
            }
            var schemeIndex: Int = uriString.indexOf(':')
            if (schemeIndex > schemeIndexLimit) {
                schemeIndex = -1
            }
            val hasAuthority: Boolean =
                schemeIndex + 2 < queryIndex && uriString.length > schemeIndex + 2 && uriString.get(
                    schemeIndex + 1
                ) == '/' && uriString.get(schemeIndex + 2) == '/'
            var pathIndex: Int
            if (hasAuthority) {
                pathIndex = uriString.indexOf('/', schemeIndex + 3)
                if (pathIndex == -1 || pathIndex > queryIndex) {
                    pathIndex = queryIndex
                }
            } else {
                pathIndex = schemeIndex + 1
            }
            indices[SCHEME_COLON] = schemeIndex
            indices[PATH] = pathIndex
            indices[QUERY] = queryIndex
            indices[FRAGMENT] = fragmentIndex
            return indices
        }
    }
}
