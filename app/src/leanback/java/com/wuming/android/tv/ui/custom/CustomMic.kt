package com.wuming.android.tv.ui.custom

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.Rect
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatImageView
import androidx.fragment.app.FragmentActivity
import com.wuming.android.tv.R
import com.wuming.android.tv.utils.KeyUtil
import com.wuming.android.tv.utils.PermissionUtil
import com.wuming.android.tv.utils.ResUtil
import com.github.bassaer.library.MDColor

open class CustomMic : AppCompatImageView {
    private var mLauncher: ActivityResultLauncher<Intent>? = null
    private var mListener: CustomTextListener? = null
    private var mRecognizer: SpeechRecognizer? = null
    private var mActivity: FragmentActivity? = null
    private var mAvailable = false
    private var mListen = false
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    private fun isAvailable(): Boolean = mAvailable

    private fun isListen(): Boolean = mListen

    private fun getIntent(): Intent =
        Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH,
        ).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)

    open fun setListener(
        activity: FragmentActivity,
        listener: CustomTextListener,
    ) {
        mActivity = activity
        mListener = listener
        listener.setDone { updateUI(false) }
        mAvailable = SpeechRecognizer.isRecognitionAvailable(activity)
        initSpeech()
    }

    private fun initSpeech() {
        if (isAvailable()) {
            initRecognizer()
        } else if (hasResolveActivity()) {
            initLauncher()
        } else {
            visibility = GONE
        }
    }

    private fun hasResolveActivity(): Boolean {
        val manager = mActivity?.packageManager ?: return false
        return getIntent().resolveActivity(manager) != null
    }

    private fun initRecognizer() {
        val activity = mActivity ?: return
        if (mRecognizer == null) mRecognizer = SpeechRecognizer.createSpeechRecognizer(activity)
        mRecognizer?.setRecognitionListener(mListener)
    }

    private fun initLauncher() {
        val activity = mActivity ?: return
        mLauncher =
            activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                    val texts = result.data!!.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    if (!texts.isNullOrEmpty()) mListener?.onResults(texts[0])
                }
            }
    }

    open fun start() {
        if (mActivity == null) return
        if (isAvailable()) startRecognizer() else launchIntent()
    }

    private fun startRecognizer() {
        val activity = mActivity ?: return
        if (mRecognizer == null) return
        PermissionUtil.requestAudio(activity) { allGranted -> if (allGranted) startListening() }
    }

    private fun startListening() {
        try {
            mRecognizer!!.startListening(getIntent())
            requestFocus()
            updateUI(true)
        } catch (ignored: Exception) {
        }
    }

    private fun launchIntent() {
        try {
            mLauncher?.launch(getIntent())
        } catch (ignored: Exception) {
        }
    }

    open fun stop() {
        val recognizer = mRecognizer ?: return
        recognizer.stopListening()
        updateUI(false)
    }

    open fun destroy() {
        mRecognizer?.destroy()
        mRecognizer = null
        mLauncher?.unregister()
        mLauncher = null
        mListener = null
        mActivity = null
    }

    private fun updateUI(listening: Boolean) {
        mListen = listening
        if (listening) {
            startAnimation(ResUtil.getAnim(R.anim.flicker))
            setColorFilter(MDColor.RED_500, PorterDuff.Mode.SRC_IN)
        } else {
            clearAnimation()
            setColorFilter(MDColor.WHITE, PorterDuff.Mode.SRC_IN)
        }
    }

    private fun onBackKey(event: KeyEvent): Boolean {
        if (!isListen() || !KeyUtil.isBackKey(event)) return false
        stop()
        return true
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus && isAvailable()) {
            start()
        } else if (!gainFocus) {
            stop()
        }
    }

    /**
     * The view holds a SpeechRecognizer, an ActivityResultLauncher and the host
     * activity. Release all three when the window goes away so a host that does
     * not call [destroy] cannot leak them.
     */
    override fun onDetachedFromWindow() {
        destroy()
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = if (onBackKey(event)) true else super.dispatchKeyEvent(event)
}
