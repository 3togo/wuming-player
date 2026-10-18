package com.wuming.tv.core.live

import java.io.InputStream

/** Initial HTTP reachability only; this does not establish sustained or decodable playback. */
object LiveStreamProbePolicy {
    /** Read failures propagate to the port, which also owns timeouts and closing the response. */
    fun hasPayload(statusCode: Int, body: InputStream?): Boolean =
        statusCode in 200..299 && body != null && body.read() >= 0
}
