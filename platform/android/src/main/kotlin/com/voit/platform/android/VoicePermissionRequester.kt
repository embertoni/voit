package com.voit.platform.android

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Thin Android-facing boundary for the voice-permission flow.
 *
 * This helper reads the ACTUAL OS grant state and defers the staged UI/system-prompt
 * decision to the pure [VoicePermissionModel]. The pure model is the single source of
 * decision truth; everything here is the un-testable (off-device) glue.
 *
 * NOT UNIT-TESTED HERE: this type touches `android.content.Context`,
 * `PackageManager.PERMISSION_GRANTED` and (via the caller) the Activity's
 * `shouldShowRequestPermissionRationale` / ActivityResult permission launcher, all of
 * which need a real device or instrumentation. Only [VoicePermissionModel] is covered by
 * the JVM unit tests in this module. Keep this class a dumb adapter: read inputs, hand
 * them to the model, act on the returned [VoicePermissionStep].
 */
class VoicePermissionRequester(
    private val context: Context,
    /**
     * Supplies the OS `shouldShowRequestPermissionRationale` flag for a manifest
     * permission string. The Activity implements this (it is Activity-scoped API); we do
     * not hold an Activity reference here. A separate [permanentlyDenied] signal is passed
     * because the OS reports rationale=false both before the first ask AND after a
     * permanent denial, so callers disambiguate using their own "asked before" bookkeeping.
     */
    private val shouldShowRationale: (manifestPermission: String) -> Boolean,
) {

    /** Maps our [VoicePermission] to the Android manifest permission string. */
    fun manifestPermission(permission: VoicePermission): String = when (permission) {
        VoicePermission.RECORD_AUDIO -> android.Manifest.permission.RECORD_AUDIO
        VoicePermission.POST_NOTIFICATIONS -> MANIFEST_POST_NOTIFICATIONS
    }

    /** Reads the live grant state for [permission] from the OS. */
    fun readState(
        permission: VoicePermission,
        permanentlyDenied: Boolean,
    ): PermissionState {
        val manifest = manifestPermission(permission)
        val granted = ContextCompat.checkSelfPermission(context, manifest) ==
            PackageManager.PERMISSION_GRANTED
        return if (granted) {
            PermissionState.Granted
        } else {
            PermissionState.NotGranted(
                shouldShowRationale = shouldShowRationale(manifest),
                permanentlyDenied = permanentlyDenied,
            )
        }
    }

    /**
     * Computes the next [VoicePermissionStep] for the whole flow, delegating entirely to
     * [VoicePermissionModel]. [permanentlyDenied] maps each [VoicePermission] to the
     * caller's "the user permanently denied this" bookkeeping.
     */
    fun nextStep(
        featureEnabled: Boolean,
        permanentlyDenied: Map<VoicePermission, Boolean> = emptyMap(),
    ): VoicePermissionStep {
        val sdkInt = Build.VERSION.SDK_INT
        val states = VoicePermissionModel.requiredPermissions(sdkInt).associateWith { permission ->
            readState(permission, permanentlyDenied[permission] == true)
        }
        return VoicePermissionModel.decideFlow(states, sdkInt, featureEnabled)
    }

    private companion object {
        /**
         * Literal to stay compilable on build tool chains whose compile SDK predates the
         * Tiramisu constant; the manifest string value is stable across SDKs.
         */
        const val MANIFEST_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    }
}
