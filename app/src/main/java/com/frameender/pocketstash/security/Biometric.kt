package com.frameender.pocketstash.security

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal

/**
 * Asks Android's own security (fingerprint, or the phone's PIN/pattern/password) to confirm
 * it's really the owner. Uses the system BiometricPrompt, so nothing about the fingerprint
 * ever reaches the app: it only learns "yes" or "no".
 */
object Biometric {

    /** The phone's screen lock alone can be asked for (used for "Forgot passcode?"). Android 10+. */
    val canAskDeviceCredential: Boolean get() = Build.VERSION.SDK_INT >= 29

    /**
     * Shows the system prompt. [onResult] gets true on success, or false plus a message to show
     * (null when the person just cancelled).
     *
     * [credentialOnly] asks for the phone's screen lock instead of a fingerprint.
     */
    fun prompt(
        activity: Activity,
        title: String,
        subtitle: String? = null,
        credentialOnly: Boolean = false,
        onResult: (ok: Boolean, error: String?) -> Unit,
    ): CancellationSignal? {
        if (Build.VERSION.SDK_INT < 28) {
            onResult(false, "Needs Android 9 or newer")
            return null
        }
        // The phone's PIN screen can briefly send the app to the background; that isn't leaving.
        activity.appLock.expectReturn()
        val builder = BiometricPrompt.Builder(activity).setTitle(title)
        subtitle?.let { builder.setSubtitle(it) }
        when {
            Build.VERSION.SDK_INT >= 30 -> builder.setAllowedAuthenticators(
                if (credentialOnly) BiometricManager.Authenticators.DEVICE_CREDENTIAL
                else BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            Build.VERSION.SDK_INT == 29 -> @Suppress("DEPRECATION") builder.setDeviceCredentialAllowed(true)
            else -> builder.setNegativeButton("Cancel", activity.mainExecutor) { _, _ -> onResult(false, null) }
        }
        val signal = CancellationSignal()
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onResult(true, null)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // (The Android 9 "Cancel" button reports through its own listener above, not here.)
                val cancelled = errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                onResult(false, if (cancelled) null else errString.toString())
            }
            // onAuthenticationFailed (one unrecognised finger) is left to the system prompt,
            // which shows "Not recognised" and lets the person try again.
        }
        runCatching { builder.build().authenticate(signal, activity.mainExecutor, callback) }
            .onFailure { onResult(false, it.message ?: "Couldn't show the fingerprint prompt") }
        return signal
    }
}
