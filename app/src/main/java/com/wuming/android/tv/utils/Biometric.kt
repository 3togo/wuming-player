package com.wuming.android.tv.utils

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.wuming.android.tv.App
import com.wuming.android.tv.R

open class Biometric {
    companion object {
        private fun getManager(): BiometricManager = BiometricManager.from(App.get())

        private fun canAuthenticate(): Int = getManager().canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)

        @JvmStatic fun enable(): Boolean = canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS

        @JvmStatic fun show(activity: FragmentActivity) {
            prompt(
                activity,
            ).authenticate(
                BiometricPrompt.PromptInfo
                    .Builder()
                    .setTitle(
                        ResUtil.getString(R.string.app_name),
                    ).setNegativeButtonText(ResUtil.getString(R.string.dialog_negative))
                    .build(),
            )
        }

        private fun prompt(activity: FragmentActivity): BiometricPrompt =
            BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence,
                    ) {
                        Notify.show(errString.toString())
                    }

                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (activity is Callback) activity.onBiometricSuccess()
                    }
                },
            )
    }

    fun interface Callback {
        fun onBiometricSuccess()
    }
}
