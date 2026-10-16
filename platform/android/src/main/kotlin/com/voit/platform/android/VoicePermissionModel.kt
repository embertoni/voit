package com.voit.platform.android

/**
 * PURE, JVM-unit-testable permission decision model for the voice feature.
 *
 * The voice feature needs two runtime permissions on recent Android versions:
 *  - [VoicePermission.RECORD_AUDIO] (always required to capture the mic)
 *  - [VoicePermission.POST_NOTIFICATIONS] (only meaningful on API 33 / Tiramisu+)
 *
 * This file intentionally contains NO `android.content.Context`, no live
 * `checkSelfPermission`, and no `shouldShowRequestPermissionRationale` call. Every
 * Android-specific value (grant state, the rationale flag, SDK_INT) is passed in as a
 * plain input, so the branching logic can be exercised by ordinary JVM unit tests.
 *
 * The thin Android-facing side that actually reads grant state and fires the runtime
 * request lives in [VoicePermissionRequester]; it delegates ALL branching here so this
 * model stays the single source of decision truth.
 */

/** The runtime permissions the voice feature may need. */
enum class VoicePermission {
    RECORD_AUDIO,
    POST_NOTIFICATIONS,
}

/** First Android SDK level on which POST_NOTIFICATIONS is a runtime permission (Tiramisu). */
const val API_POST_NOTIFICATIONS = 33

/**
 * Current state of a single permission, as observed from the OS.
 *
 * [shouldShowRationale] mirrors the OS `shouldShowRequestPermissionRationale` flag and is
 * only consulted while the permission is still [NotGranted].
 */
sealed interface PermissionState {
    /** The permission has been granted. */
    data object Granted : PermissionState

    /**
     * The permission is not granted. [shouldShowRationale] is the OS hint that the user
     * previously denied once (so we should explain why before re-asking). When the user
     * has permanently denied (checked "don't ask again"), the OS reports this flag as
     * false AND the request can no longer surface a system dialog; see [permanentlyDenied].
     */
    data class NotGranted(
        val shouldShowRationale: Boolean,
        val permanentlyDenied: Boolean,
    ) : PermissionState
}

/** The next UI step the caller should take for the voice-permission flow. */
enum class VoicePermissionStep {
    /** The voice feature is disabled, so we must not request anything. */
    DO_NOTHING_FEATURE_DISABLED,

    /** Show the staged rationale sheet BEFORE invoking the system permission prompt. */
    SHOW_RATIONALE,

    /** Fire the OS runtime permission request. */
    REQUEST,

    /** Everything needed is granted; proceed into the voice flow. */
    PROCEED,

    /** The permission was permanently denied; point the user at app Settings. */
    SHOW_SETTINGS_HINT,

    /** POST_NOTIFICATIONS is not a runtime permission below API 33; nothing to do for it. */
    SKIP_NOTIFICATIONS_BELOW_33,
}

/**
 * The pure decision model. All methods are side-effect free and take plain inputs.
 */
object VoicePermissionModel {

    /**
     * Decides the next step for a SINGLE [permission].
     *
     * Rules encoded here (per the Phase 2 spec):
     *  - If the voice feature is NOT [featureEnabled] -> [VoicePermissionStep.DO_NOTHING_FEATURE_DISABLED]
     *    (we request permissions ONLY when the feature is enabled).
     *  - POST_NOTIFICATIONS below API 33 -> [VoicePermissionStep.SKIP_NOTIFICATIONS_BELOW_33].
     *  - Granted -> [VoicePermissionStep.PROCEED].
     *  - Not granted + permanently denied -> [VoicePermissionStep.SHOW_SETTINGS_HINT].
     *  - Not granted + OS says show rationale -> [VoicePermissionStep.SHOW_RATIONALE]
     *    (staged rationale ALWAYS precedes the system prompt).
     *  - Not granted + no rationale yet -> [VoicePermissionStep.REQUEST].
     */
    fun decide(
        permission: VoicePermission,
        state: PermissionState,
        sdkInt: Int,
        featureEnabled: Boolean,
    ): VoicePermissionStep {
        if (!featureEnabled) {
            return VoicePermissionStep.DO_NOTHING_FEATURE_DISABLED
        }

        if (permission == VoicePermission.POST_NOTIFICATIONS && sdkInt < API_POST_NOTIFICATIONS) {
            return VoicePermissionStep.SKIP_NOTIFICATIONS_BELOW_33
        }

        return when (state) {
            is PermissionState.Granted -> VoicePermissionStep.PROCEED
            is PermissionState.NotGranted -> when {
                state.permanentlyDenied -> VoicePermissionStep.SHOW_SETTINGS_HINT
                state.shouldShowRationale -> VoicePermissionStep.SHOW_RATIONALE
                else -> VoicePermissionStep.REQUEST
            }
        }
    }

    /**
     * Returns the ordered list of permissions that actually apply for the given [sdkInt].
     * RECORD_AUDIO always applies; POST_NOTIFICATIONS only from API 33 upward.
     */
    fun requiredPermissions(sdkInt: Int): List<VoicePermission> = buildList {
        add(VoicePermission.RECORD_AUDIO)
        if (sdkInt >= API_POST_NOTIFICATIONS) {
            add(VoicePermission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Aggregates the per-permission decisions into the single next step for the whole flow.
     *
     * Priority (highest first): feature disabled > settings hint > rationale > request >
     * proceed. POST_NOTIFICATIONS below API 33 is treated as satisfied and ignored. This
     * guarantees the staged rationale is shown before any system prompt and that a
     * permanently-denied required permission routes the user to Settings.
     */
    fun decideFlow(
        states: Map<VoicePermission, PermissionState>,
        sdkInt: Int,
        featureEnabled: Boolean,
    ): VoicePermissionStep {
        if (!featureEnabled) {
            return VoicePermissionStep.DO_NOTHING_FEATURE_DISABLED
        }

        val steps = requiredPermissions(sdkInt).map { permission ->
            val state = states[permission] ?: PermissionState.NotGranted(
                shouldShowRationale = false,
                permanentlyDenied = false,
            )
            decide(permission, state, sdkInt, featureEnabled)
        }

        return when {
            steps.contains(VoicePermissionStep.SHOW_SETTINGS_HINT) -> VoicePermissionStep.SHOW_SETTINGS_HINT
            steps.contains(VoicePermissionStep.SHOW_RATIONALE) -> VoicePermissionStep.SHOW_RATIONALE
            steps.contains(VoicePermissionStep.REQUEST) -> VoicePermissionStep.REQUEST
            else -> VoicePermissionStep.PROCEED
        }
    }
}
