package com.voit.platform.android

/**
 * PURE, JVM-unit-testable policy + state machine for the MICROPHONE FOREGROUND SERVICE that
 * backs always-on listening (Phase 4). It holds the rules for WHEN the service may legally
 * start, WHAT foreground-service type/permissions a given Android version requires, and the
 * service's lifecycle STATE - with every Android value (SDK_INT, permission grants) passed
 * in as a plain input.
 *
 * This is the Phase-4 analogue of the Phase-2/3 pure controllers and [VoicePermissionModel]:
 * NO `android.app.Service`, no `startForeground`, no `NotificationManager`, no Context. The
 * thin Android-facing service shell (added when a real device is available) delegates every
 * start/stop/type decision here, so this model is the single source of lifecycle truth and
 * is fully unit-tested off-device.
 *
 * Why a model and not just the Service: Android 14 (API 34) requires a declared
 * `foregroundServiceType="microphone"` AND the `FOREGROUND_SERVICE_MICROPHONE` permission,
 * and the service may only enter the foreground while the `RECORD_AUDIO` grant is held. A
 * misconfigured start throws `ForegroundServiceStartNotAllowedException` /
 * `SecurityException` at runtime. Encoding the eligibility rules as pure logic lets us pin
 * them with ordinary tests instead of discovering them only on a device.
 *
 * PRIVACY: this model governs the LIFECYCLE only. It never touches audio; the raw-audio
 * invariant is enforced in `core:voice` (`RecordingController` / `WakeWordController`).
 * Diagnostics are stable codes only (see [MicServiceErrorCode]), never captured content.
 */

/** Foreground-service type identifiers relevant to the mic service. */
enum class ForegroundServiceType {
    /** The `microphone` foreground-service type (required to use the mic in foreground). */
    MICROPHONE,
}

/** Lifecycle state of the mic foreground service, exposed to the app. */
enum class MicServiceState {
    /** Not running. */
    STOPPED,

    /** Requested to start; waiting to enter the foreground. */
    STARTING,

    /** Running in the foreground with an ongoing notification. */
    RUNNING,

    /** Asked to stop; tearing down the foreground + notification. */
    STOPPING,
}

/**
 * Stable diagnostic codes for a refused/failed start. CODES ONLY - never captured audio -
 * consistent with the diagnostics-are-codes rule.
 */
object MicServiceErrorCode {
    /** Listening is not enabled by the user, so the service must not start. */
    const val FEATURE_DISABLED: String = "FEATURE_DISABLED"

    /** `RECORD_AUDIO` is not granted; a mic foreground service may not run. */
    const val RECORD_AUDIO_NOT_GRANTED: String = "RECORD_AUDIO_NOT_GRANTED"

    /**
     * API 34+ requires the `FOREGROUND_SERVICE_MICROPHONE` permission in addition to the
     * mic grant; it is missing.
     */
    const val FGS_MIC_PERMISSION_MISSING: String = "FGS_MIC_PERMISSION_MISSING"

    /** On API 33+ the ongoing notification needs POST_NOTIFICATIONS to be visible. */
    const val POST_NOTIFICATIONS_NOT_GRANTED: String = "POST_NOTIFICATIONS_NOT_GRANTED"
}

/**
 * Inputs describing the live OS + settings conditions for a start attempt. All plain values.
 *
 * @param featureEnabled the user's always-on listening toggle (`AppSettings.listeningEnabled`).
 * @param recordAudioGranted whether `RECORD_AUDIO` is currently granted.
 * @param fgsMicPermissionGranted whether `FOREGROUND_SERVICE_MICROPHONE` is granted (only
 *   meaningful on API 34+; on lower APIs it is not a declared runtime concern).
 * @param postNotificationsGranted whether `POST_NOTIFICATIONS` is granted (only gated on API 33+).
 * @param sdkInt the live `Build.VERSION.SDK_INT`.
 */
data class MicServiceConditions(
    val featureEnabled: Boolean,
    val recordAudioGranted: Boolean,
    val fgsMicPermissionGranted: Boolean,
    val postNotificationsGranted: Boolean,
    val sdkInt: Int,
)

/** The outcome of evaluating a start attempt. */
sealed interface MicServiceStartDecision {
    /**
     * The service may start. [serviceType] is the foreground-service type to declare on
     * API 29+ (always [ForegroundServiceType.MICROPHONE] here); [notificationVisible] is
     * false when POST_NOTIFICATIONS is missing on API 33+ (the service still runs - the
     * notification is simply not shown, which Android permits - so we surface the gap as a
     * non-fatal hint rather than refusing the start).
     */
    data class Allowed(
        val serviceType: ForegroundServiceType,
        val notificationVisible: Boolean,
    ) : MicServiceStartDecision

    /** The service must NOT start; [errorCode] is a stable [MicServiceErrorCode]. */
    data class Refused(val errorCode: String) : MicServiceStartDecision
}

/**
 * The pure mic-foreground-service policy. Side-effect free; all inputs are plain values.
 */
object ForegroundMicServiceModel {

    /** First SDK level requiring an explicit foreground-service type. */
    const val API_FGS_TYPE = 29 // Android 10 (Q)

    /** First SDK level requiring the `FOREGROUND_SERVICE_MICROPHONE` permission. */
    const val API_FGS_MIC_PERMISSION = 34 // Android 14 (UpsideDownCake)

    /** First SDK level on which POST_NOTIFICATIONS gates the ongoing notification. */
    const val API_POST_NOTIFICATIONS = 33 // Android 13 (Tiramisu)

    /**
     * Decide whether the mic foreground service may start under the given [conditions].
     *
     * Hard refusals (service must not start):
     *  - feature disabled,
     *  - `RECORD_AUDIO` not granted,
     *  - API 34+ and `FOREGROUND_SERVICE_MICROPHONE` not granted.
     *
     * Soft gap (service still starts): API 33+ and POST_NOTIFICATIONS not granted - the
     * ongoing notification cannot be shown, but Android still allows the foreground service;
     * [MicServiceStartDecision.Allowed.notificationVisible] is false so the caller can
     * surface a "notification hidden" hint without blocking listening.
     */
    fun evaluateStart(conditions: MicServiceConditions): MicServiceStartDecision {
        if (!conditions.featureEnabled) {
            return MicServiceStartDecision.Refused(MicServiceErrorCode.FEATURE_DISABLED)
        }
        if (!conditions.recordAudioGranted) {
            return MicServiceStartDecision.Refused(MicServiceErrorCode.RECORD_AUDIO_NOT_GRANTED)
        }
        if (conditions.sdkInt >= API_FGS_MIC_PERMISSION && !conditions.fgsMicPermissionGranted) {
            return MicServiceStartDecision.Refused(MicServiceErrorCode.FGS_MIC_PERMISSION_MISSING)
        }

        val notificationVisible =
            conditions.sdkInt < API_POST_NOTIFICATIONS || conditions.postNotificationsGranted

        return MicServiceStartDecision.Allowed(
            serviceType = ForegroundServiceType.MICROPHONE,
            notificationVisible = notificationVisible,
        )
    }

    /** Whether an explicit foreground-service type must be declared at startForeground time. */
    fun requiresServiceType(sdkInt: Int): Boolean = sdkInt >= API_FGS_TYPE

    /**
     * The legal next [MicServiceState] for a transition, or the current state unchanged when
     * the transition is a no-op (idempotent). This keeps the Android service shell from
     * double-starting or stopping an already-stopped service.
     *
     * Legal transitions:
     *  - STOPPED --start--> STARTING
     *  - STARTING --enteredForeground--> RUNNING
     *  - RUNNING / STARTING --stop--> STOPPING
     *  - STOPPING --stopped--> STOPPED
     * Any other (current, transition) pair returns [current] unchanged.
     */
    fun nextState(current: MicServiceState, transition: MicServiceTransition): MicServiceState =
        when (transition) {
            MicServiceTransition.START ->
                if (current == MicServiceState.STOPPED) MicServiceState.STARTING else current
            MicServiceTransition.ENTERED_FOREGROUND ->
                if (current == MicServiceState.STARTING) MicServiceState.RUNNING else current
            MicServiceTransition.STOP ->
                if (current == MicServiceState.STARTING || current == MicServiceState.RUNNING) {
                    MicServiceState.STOPPING
                } else {
                    current
                }
            MicServiceTransition.STOPPED ->
                if (current == MicServiceState.STOPPING) MicServiceState.STOPPED else current
        }
}

/** Lifecycle transitions fed to [ForegroundMicServiceModel.nextState]. */
enum class MicServiceTransition {
    /** The app requested the service to start. */
    START,

    /** The service successfully entered the foreground (startForeground returned). */
    ENTERED_FOREGROUND,

    /** The app requested the service to stop. */
    STOP,

    /** The service finished tearing down. */
    STOPPED,
}
