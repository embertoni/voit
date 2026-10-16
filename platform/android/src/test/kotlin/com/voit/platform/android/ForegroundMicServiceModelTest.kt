package com.voit.platform.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure JVM unit tests for [ForegroundMicServiceModel]. No Android Service / Notification /
 * Robolectric: every input is a plain value. Mirrors the VoicePermissionModel test style.
 */
class ForegroundMicServiceModelTest {

    private val android14 = 34 // requires FOREGROUND_SERVICE_MICROPHONE + type
    private val android13 = 33 // POST_NOTIFICATIONS gates the notification
    private val android12 = 31 // no FGS_MIC permission, notification ungated
    private val android9 = 28 // below the FGS-type requirement

    private fun conditions(
        featureEnabled: Boolean = true,
        recordAudioGranted: Boolean = true,
        fgsMicPermissionGranted: Boolean = true,
        postNotificationsGranted: Boolean = true,
        sdkInt: Int = android14,
    ) = MicServiceConditions(
        featureEnabled = featureEnabled,
        recordAudioGranted = recordAudioGranted,
        fgsMicPermissionGranted = fgsMicPermissionGranted,
        postNotificationsGranted = postNotificationsGranted,
        sdkInt = sdkInt,
    )

    // --- evaluateStart: hard refusals -----------------------------------------------------

    @Test
    fun featureDisabled_refusesStart() {
        val decision = ForegroundMicServiceModel.evaluateStart(conditions(featureEnabled = false))
        assertEquals(
            MicServiceStartDecision.Refused(MicServiceErrorCode.FEATURE_DISABLED),
            decision,
        )
    }

    @Test
    fun recordAudioNotGranted_refusesStart() {
        val decision = ForegroundMicServiceModel.evaluateStart(conditions(recordAudioGranted = false))
        assertEquals(
            MicServiceStartDecision.Refused(MicServiceErrorCode.RECORD_AUDIO_NOT_GRANTED),
            decision,
        )
    }

    @Test
    fun api34_withoutFgsMicPermission_refusesStart() {
        val decision = ForegroundMicServiceModel.evaluateStart(
            conditions(fgsMicPermissionGranted = false, sdkInt = android14),
        )
        assertEquals(
            MicServiceStartDecision.Refused(MicServiceErrorCode.FGS_MIC_PERMISSION_MISSING),
            decision,
        )
    }

    @Test
    fun belowApi34_withoutFgsMicPermission_stillStarts() {
        // FOREGROUND_SERVICE_MICROPHONE is only required on API 34+; on API 31 its absence
        // must NOT block the start.
        val decision = ForegroundMicServiceModel.evaluateStart(
            conditions(fgsMicPermissionGranted = false, sdkInt = android12),
        )
        assertTrue(decision is MicServiceStartDecision.Allowed)
    }

    // --- evaluateStart: allowed + soft notification gap -----------------------------------

    @Test
    fun allInGranted_allowsWithMicTypeAndVisibleNotification() {
        val decision = ForegroundMicServiceModel.evaluateStart(conditions())
        val allowed = decision as MicServiceStartDecision.Allowed
        assertEquals(ForegroundServiceType.MICROPHONE, allowed.serviceType)
        assertTrue(allowed.notificationVisible)
    }

    @Test
    fun api33_withoutPostNotifications_stillStartsButNotificationHidden() {
        val decision = ForegroundMicServiceModel.evaluateStart(
            conditions(postNotificationsGranted = false, sdkInt = android13),
        )
        val allowed = decision as MicServiceStartDecision.Allowed
        assertEquals(ForegroundServiceType.MICROPHONE, allowed.serviceType)
        // Soft gap: the service runs, but the ongoing notification cannot be shown.
        assertFalse(allowed.notificationVisible)
    }

    @Test
    fun belowApi33_notificationAlwaysVisible_regardlessOfPostNotifications() {
        val decision = ForegroundMicServiceModel.evaluateStart(
            conditions(postNotificationsGranted = false, sdkInt = android12),
        )
        val allowed = decision as MicServiceStartDecision.Allowed
        assertTrue(allowed.notificationVisible)
    }

    // --- requiresServiceType --------------------------------------------------------------

    @Test
    fun serviceTypeRequiredFromApi29Up() {
        assertTrue(ForegroundMicServiceModel.requiresServiceType(android14))
        assertTrue(ForegroundMicServiceModel.requiresServiceType(29))
        assertFalse(ForegroundMicServiceModel.requiresServiceType(android9))
    }

    // --- nextState: lifecycle transitions -------------------------------------------------

    @Test
    fun legalLifecycle_stoppedToRunningToStopped() {
        var s = MicServiceState.STOPPED
        s = ForegroundMicServiceModel.nextState(s, MicServiceTransition.START)
        assertEquals(MicServiceState.STARTING, s)
        s = ForegroundMicServiceModel.nextState(s, MicServiceTransition.ENTERED_FOREGROUND)
        assertEquals(MicServiceState.RUNNING, s)
        s = ForegroundMicServiceModel.nextState(s, MicServiceTransition.STOP)
        assertEquals(MicServiceState.STOPPING, s)
        s = ForegroundMicServiceModel.nextState(s, MicServiceTransition.STOPPED)
        assertEquals(MicServiceState.STOPPED, s)
    }

    @Test
    fun startIsIdempotent_whenAlreadyStartingOrRunning() {
        assertEquals(
            MicServiceState.STARTING,
            ForegroundMicServiceModel.nextState(MicServiceState.STARTING, MicServiceTransition.START),
        )
        assertEquals(
            MicServiceState.RUNNING,
            ForegroundMicServiceModel.nextState(MicServiceState.RUNNING, MicServiceTransition.START),
        )
    }

    @Test
    fun stopFromStopped_isNoOp() {
        assertEquals(
            MicServiceState.STOPPED,
            ForegroundMicServiceModel.nextState(MicServiceState.STOPPED, MicServiceTransition.STOP),
        )
    }

    @Test
    fun enteredForeground_onlyPromotesFromStarting() {
        // A stray ENTERED_FOREGROUND while STOPPED must not flip us to RUNNING.
        assertEquals(
            MicServiceState.STOPPED,
            ForegroundMicServiceModel.nextState(
                MicServiceState.STOPPED,
                MicServiceTransition.ENTERED_FOREGROUND,
            ),
        )
    }

    @Test
    fun stopFromStarting_goesToStopping() {
        // Cancelling before the service finished entering the foreground is legal.
        assertEquals(
            MicServiceState.STOPPING,
            ForegroundMicServiceModel.nextState(MicServiceState.STARTING, MicServiceTransition.STOP),
        )
    }
}
