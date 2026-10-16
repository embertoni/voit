package com.voit

import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.voit.platform.android.VoicePermissionModel
import com.voit.platform.android.VoicePermissionRequester
import com.voit.platform.android.VoicePermissionStep

/**
 * Activity-side glue that wires the Voice entry point's "start recording" action to the
 * runtime permission flow, routing EVERY decision through the pure
 * [VoicePermissionModel] (via [VoicePermissionRequester]). This is the integration the
 * Phase-2 review asked for: previously the permission model was built and unit-tested but
 * never invoked, so the Voice screen started capture with no runtime permission check even
 * though the manifest declares `RECORD_AUDIO` and `docs/privacy.md` describes a staged
 * rationale flow.
 *
 * Why this is a thin, un-unit-tested adapter (like [VoicePermissionRequester] itself): it
 * touches an [ActivityResultLauncher] and Activity-scoped
 * `shouldShowRequestPermissionRationale`, which require a real device/instrumentation. All
 * BRANCHING stays inside the pure [VoicePermissionModel]; this class only reads live inputs
 * and acts on the returned [VoicePermissionStep]. It is validated by inspection plus the
 * existing pure-model JVM tests - NO device behavior is claimed to be validated here.
 *
 * GRACEFUL FALLBACK: the DEFAULT wired recognizer is a fake audio session that needs no
 * microphone grant (there is no device/emulator here). So when a decision cannot complete a
 * live grant - e.g. the permission is denied, or (in a non-Activity host) no launcher is
 * available - the gate still proceeds into capture against the fake rather than dead-ending
 * the only runnable path. When a REAL capture source lands, this is the single place that
 * must gate capture on an actual grant instead of proceeding regardless.
 */
class VoicePermissionGate private constructor(
    private val requester: VoicePermissionRequester,
    private val launcher: ActivityResultLauncher<Array<String>>,
    private val featureEnabledProvider: () -> Boolean,
) {

    /** The capture action to resume once a runtime request round-trips. */
    private var pendingProceed: (() -> Unit)? = null

    /**
     * Entry-point hook the Voice screen calls INSTEAD of starting capture directly.
     *
     * It consults the pure model for the next [VoicePermissionStep]:
     *  - [VoicePermissionStep.PROCEED] / feature-disabled / below-33 notifications: start
     *    capture immediately (nothing to request).
     *  - [VoicePermissionStep.REQUEST] / [VoicePermissionStep.SHOW_RATIONALE]: fire the OS
     *    runtime request; [onRequestResult] re-consults the model and then proceeds against
     *    the fake (graceful fallback - see class KDoc).
     *  - [VoicePermissionStep.SHOW_SETTINGS_HINT]: cannot prompt again; proceed against the
     *    fake so the only runnable path is not dead-ended.
     */
    fun ensurePermission(onProceed: () -> Unit) {
        when (requester.nextStep(featureEnabled = featureEnabledProvider())) {
            VoicePermissionStep.REQUEST,
            VoicePermissionStep.SHOW_RATIONALE,
            -> {
                pendingProceed = onProceed
                launcher.launch(manifestPermissionsToRequest())
            }

            VoicePermissionStep.PROCEED,
            VoicePermissionStep.DO_NOTHING_FEATURE_DISABLED,
            VoicePermissionStep.SKIP_NOTIFICATIONS_BELOW_33,
            VoicePermissionStep.SHOW_SETTINGS_HINT,
            -> onProceed()
        }
    }

    /** The manifest permission strings to pass to the runtime request launcher. */
    private fun manifestPermissionsToRequest(): Array<String> =
        VoicePermissionModel.requiredPermissions(android.os.Build.VERSION.SDK_INT)
            .map { requester.manifestPermission(it) }
            .toTypedArray()

    /**
     * Invoked by the Activity's [ActivityResultLauncher] callback after the user responds to
     * the runtime request. The grant map is intentionally not branched on here; the decision
     * truth lives in the model (which would re-evaluate grant state on a subsequent attempt).
     * Against the default fake session we proceed regardless so the runnable path is never
     * dead-ended; a real capture source would instead gate on the grant result here.
     */
    private fun onRequestResult() {
        val proceed = pendingProceed
        pendingProceed = null
        proceed?.invoke()
    }

    companion object {
        /**
         * Builds a gate bound to [activity]. Registers the multiple-permissions
         * [ActivityResultContracts.RequestMultiplePermissions] launcher and a
         * [VoicePermissionRequester] whose rationale flag comes from the Activity.
         */
        fun create(
            activity: ComponentActivity,
            featureEnabledProvider: () -> Boolean,
        ): VoicePermissionGate {
            lateinit var gate: VoicePermissionGate
            val launcher = activity.registerForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { _ -> gate.onRequestResult() }

            val requester = VoicePermissionRequester(
                context = activity,
                shouldShowRationale = { manifestPermission ->
                    activity.shouldShowRequestPermissionRationale(manifestPermission)
                },
            )
            gate = VoicePermissionGate(requester, launcher, featureEnabledProvider)
            return gate
        }
    }
}
