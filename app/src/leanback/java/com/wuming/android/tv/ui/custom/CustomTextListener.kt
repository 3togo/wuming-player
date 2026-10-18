package com.wuming.android.tv.ui.custom

import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.TextWatcher
import java.util.Collections

abstract class CustomTextListener :
    TextWatcher,
    RecognitionListener {
    private var done: Runnable? = null

    open fun setDone(done: Runnable?) {
        this.done = done
    }

    private fun done() {
        done?.run()
    }

    private fun parse(bundle: Bundle?): String {
        val texts = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        return if (texts.isNullOrEmpty()) "" else texts[0].trim { it <= ' ' }
    }

    override fun onReadyForSpeech(params: Bundle?) {}

    override fun onBeginningOfSpeech() {}

    override fun onRmsChanged(rmsdB: Float) {}

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {}

    override fun onPartialResults(partialResults: Bundle?) {}

    override fun onEvent(
        eventType: Int,
        params: Bundle?,
    ) {}

    override fun onError(error: Int) {
        done()
        onResults("")
    }

    override fun onResults(results: Bundle?) {
        done()
        onResults(parse(results))
    }

    override fun beforeTextChanged(
        s: CharSequence?,
        start: Int,
        count: Int,
        after: Int,
    ) {}

    override fun onTextChanged(
        s: CharSequence?,
        start: Int,
        before: Int,
        count: Int,
    ) {}

    override fun afterTextChanged(s: Editable?) {}

    open fun onResults(result: String) {}
}
