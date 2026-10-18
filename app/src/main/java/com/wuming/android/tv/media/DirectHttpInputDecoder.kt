package com.wuming.android.tv.media

import com.wuming.tv.core.media.InputDecoder

/** Android caller API for the shared direct-HTTP decoder. */
class DirectHttpInputDecoder : InputDecoder by com.wuming.tv.core.media.DirectHttpInputDecoder() {
    companion object { const val ADAPTER_ID = com.wuming.tv.core.media.DirectHttpInputDecoder.ADAPTER_ID }
}
