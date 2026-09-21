// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/BiometricAuthenticator.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.utils

import android.content.Context
import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus

/**
 * Android Biometric Authentication (BiometricPrompt / KeyguardManager)
 *
 * Quarantined because mobile biometric hardware (fingerprint/face unlock/KeyguardManager)
 * is Android-specific and does not apply to Linux desktop. Desktop security is handled
 * via PinSecurity or application-level authentication.
 */
@PlatformQuarantine(
    reason = "Mobile biometric hardware (fingerprint/face unlock/KeyguardManager) is Android-specific; Linux desktop uses PinSecurity",
    upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/BiometricAuthenticator.kt",
    status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
)
object BiometricAuthenticator {
    const val TAG = "cs3Auth"

    interface BiometricCallback {
        fun onAuthenticationSuccess()
        fun onAuthenticationError()
    }

    fun deviceHasPasswordPinLock(context: Context? = null): Boolean = false

    fun isAuthEnabled(ctx: Context? = null): Boolean = false

    fun startBiometricAuthentication(
        activity: Any? = null,
        title: Int = 0,
        setDeviceCred: Boolean = false
    ) {
        // Mobile biometric authentication hardware is not applicable to Linux desktop
    }
}
