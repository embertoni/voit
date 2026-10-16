package com.voit.platform.android

/**
 * PURE, JVM-unit-testable decision model for the GLOBAL OVERLAY permission
 * (`SYSTEM_ALERT_WINDOW` / "Display over other apps").
 *
 * This is the Phase-4 analogue of [VoicePermissionModel]: it contains NO
 * `android.content.Context`, no live `Settings.canDrawOverlays`, and no `Intent`. Every
 * Android-specific value (the overlay-granted flag, SDK_INT, whether the user has enabled
 * the feature) is passed in as a plain input, so all branching is exercised by ordinary
 * JVM unit tests. The thin Android-facing side that reads `canDrawOverlays` and launches
 * the `ACTION_MANAGE_OVERLAY_PERMISSION` settings screen delegates ALL branching here.
 *
 * Unlike a normal runtime permission, `SYSTEM_ALERT_WINDOW` cannot be requested with a
 * system dialog: the user must toggle it in a dedicated Settings screen. So there is no
 * "show rationale then request" dance - the decision is simply grant? proceed : send the
 * user to the settings screen (after an in-app explanation).
 *
 * PRIVACY / SCOPE NOTE: the overlay is Voit's own small listening-status affordance. It is
 * NOT a screen reader and NOT an accessibility service; it never reads other apps' screen
 * contents (see docs/privacy.md "No screen scraping"). This model only governs whether
 * Voit may draw its OWN overlay, never any capture of the screen.
 */

/** The next UI step the caller should take for the overlay-permission flow. */
enum class OverlayPermissionStep {
    /** The overlay feature is disabled by the user, so do nothing. */
    DO_NOTHING_FEATURE_DISABLED,

    /**
     * Overlays are not a user-grantable permission below API 23 (they were install-time);
     * on `minSdk 26` this never fires, but it is modeled for completeness/clarity.
     */
    GRANTED_IMPLICITLY_BELOW_23,

    /** Show the in-app explanation BEFORE sending the user to the system settings screen. */
    SHOW_RATIONALE,

    /** Launch the `ACTION_MANAGE_OVERLAY_PERMISSION` settings screen for the user to toggle. */
    OPEN_OVERLAY_SETTINGS,

    /** The overlay permission is granted; Voit may draw its overlay. */
    PROCEED,
}

/**
 * The pure overlay-permission decision model. All methods are side-effect free and take
 * plain inputs.
 */
object OverlayPermissionModel {

    /** First Android SDK level on which `SYSTEM_ALERT_WINDOW` became a user-managed grant. */
    const val API_MANAGE_OVERLAY = 23

    /**
     * Decide the next overlay-permission step.
     *
     * Rules:
     *  - Feature disabled -> [OverlayPermissionStep.DO_NOTHING_FEATURE_DISABLED] (we never
     *    nudge the user toward an overlay they did not ask for).
     *  - Below API 23 -> [OverlayPermissionStep.GRANTED_IMPLICITLY_BELOW_23] (install-time).
     *  - Granted -> [OverlayPermissionStep.PROCEED].
     *  - Not granted + explanation not yet acknowledged -> [OverlayPermissionStep.SHOW_RATIONALE].
     *  - Not granted + explanation acknowledged -> [OverlayPermissionStep.OPEN_OVERLAY_SETTINGS].
     *
     * @param canDrawOverlays the live `Settings.canDrawOverlays` value, passed in as a plain flag.
     * @param rationaleAcknowledged whether the user has already seen/accepted the in-app
     *   explanation this flow (so we go straight to settings instead of re-explaining).
     */
    fun decide(
        featureEnabled: Boolean,
        canDrawOverlays: Boolean,
        sdkInt: Int,
        rationaleAcknowledged: Boolean,
    ): OverlayPermissionStep {
        if (!featureEnabled) {
            return OverlayPermissionStep.DO_NOTHING_FEATURE_DISABLED
        }
        if (sdkInt < API_MANAGE_OVERLAY) {
            return OverlayPermissionStep.GRANTED_IMPLICITLY_BELOW_23
        }
        if (canDrawOverlays) {
            return OverlayPermissionStep.PROCEED
        }
        return if (rationaleAcknowledged) {
            OverlayPermissionStep.OPEN_OVERLAY_SETTINGS
        } else {
            OverlayPermissionStep.SHOW_RATIONALE
        }
    }
}
