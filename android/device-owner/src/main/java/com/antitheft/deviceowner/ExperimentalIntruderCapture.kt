package com.antitheft.deviceowner

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Experimental Intruder Capture Research & Implementation.
 * Evaluates feasibility of capturing photo after consecutive failed unlock attempts
 * via DeviceAdminReceiver.onPasswordFailed.
 *
 * RESEARCH FINDINGS & OS CONSTRAINTS (Android 9 to 14):
 * 1. Background Camera Sandbox (Android 9+ / API 28+):
 *    Android explicitly cuts off Camera2 sensor access when a package UID transitions
 *    to background. Attempting to open a CameraDevice while keyguard is active fails.
 * 2. Background Activity Launches (Android 10+ / API 29+):
 *    Starting an invisible Activity from onPasswordFailed is blocked by Background Activity
 *    Launch (BAL) restrictions.
 * 3. Foreground Service Types (Android 14+ / API 34+):
 *    Accessing camera from a ForegroundService requires android:foregroundServiceType="camera"
 *    and cannot be launched from background without an active while-in-use exception.
 * 4. Privacy Indicators:
 *    Android 12+ enforces a persistent green camera privacy indicator in the status bar;
 *    silent or covert capture is impossible by design on non-rooted Android.
 *
 * VERDICT: Marked EXPERIMENTAL. Safely records failed attempt telemetry and logs
 * security events, while documenting OS camera sandbox constraints.
 */
class ExperimentalIntruderCapture {

    data class IntruderCaptureFeasibility(
        val hasCameraHardware: Boolean,
        val isSupportedByOS: Boolean,
        val requiresCameraPermission: Boolean,
        val isBackgroundCameraPermitted: Boolean,
        val explanation: String
    )

    fun evaluateFeasibility(context: Context): IntruderCaptureFeasibility {
        val hasCamera = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
        val apiLevel = Build.VERSION.SDK_INT

        val isBackgroundCameraPermitted = apiLevel < Build.VERSION_CODES.P // Deprecated since Android 9

        return IntruderCaptureFeasibility(
            hasCameraHardware = hasCamera,
            isSupportedByOS = false,
            requiresCameraPermission = true,
            isBackgroundCameraPermitted = isBackgroundCameraPermitted,
            explanation = "Modern Android ($apiLevel) strictly isolates camera hardware from background UIDs during keyguard lock. " +
                    "Foreground service camera access requires 'foregroundServiceType=camera' and user-initiated transition. " +
                    "Covert background photo capture is intentionally blocked by the Android security framework."
        )
    }

    fun handleFailedUnlockEvent(context: Context, consecutiveAttempts: Int) {
        Log.w(TAG, "INTRUDER_ALERT_EVALUATED: Consecutive failed attempts: $consecutiveAttempts")
        val feasibility = evaluateFeasibility(context)
        Log.i(TAG, "Intruder photo policy: ${feasibility.explanation}")
    }

    companion object {
        private const val TAG = "ExperimentalIntruder"
    }
}
