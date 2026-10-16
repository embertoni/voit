package com.voit.platform.android

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure JVM unit tests for [OverlayPermissionModel]. No Android Context / Settings /
 * Robolectric: every input is a plain value. Mirrors the VoicePermissionModel test style.
 */
class OverlayPermissionModelTest {

    private val recentSdk = 34 // Android 14
    private val legacySdk = 22 // below API 23 (overlay was install-time)

    @Test
    fun featureDisabled_doesNothing_evenWhenNotGranted() {
        val step = OverlayPermissionModel.decide(
            featureEnabled = false,
            canDrawOverlays = false,
            sdkInt = recentSdk,
            rationaleAcknowledged = false,
        )
        assertEquals(OverlayPermissionStep.DO_NOTHING_FEATURE_DISABLED, step)
    }

    @Test
    fun featureDisabled_doesNothing_evenWhenGranted() {
        val step = OverlayPermissionModel.decide(
            featureEnabled = false,
            canDrawOverlays = true,
            sdkInt = recentSdk,
            rationaleAcknowledged = true,
        )
        assertEquals(OverlayPermissionStep.DO_NOTHING_FEATURE_DISABLED, step)
    }

    @Test
    fun belowApi23_isGrantedImplicitly() {
        val step = OverlayPermissionModel.decide(
            featureEnabled = true,
            canDrawOverlays = false,
            sdkInt = legacySdk,
            rationaleAcknowledged = false,
        )
        assertEquals(OverlayPermissionStep.GRANTED_IMPLICITLY_BELOW_23, step)
    }

    @Test
    fun enabled_granted_proceeds() {
        val step = OverlayPermissionModel.decide(
            featureEnabled = true,
            canDrawOverlays = true,
            sdkInt = recentSdk,
            rationaleAcknowledged = false,
        )
        assertEquals(OverlayPermissionStep.PROCEED, step)
    }

    @Test
    fun enabled_notGranted_noAck_showsRationaleFirst() {
        val step = OverlayPermissionModel.decide(
            featureEnabled = true,
            canDrawOverlays = false,
            sdkInt = recentSdk,
            rationaleAcknowledged = false,
        )
        assertEquals(OverlayPermissionStep.SHOW_RATIONALE, step)
    }

    @Test
    fun enabled_notGranted_ackalready_opensSettings() {
        val step = OverlayPermissionModel.decide(
            featureEnabled = true,
            canDrawOverlays = false,
            sdkInt = recentSdk,
            rationaleAcknowledged = true,
        )
        assertEquals(OverlayPermissionStep.OPEN_OVERLAY_SETTINGS, step)
    }

    @Test
    fun rationaleAlwaysPrecedesSettings_whenNotGranted() {
        // The staged explanation must come before the settings screen: with no ack the step
        // is SHOW_RATIONALE, and only after ack does it become OPEN_OVERLAY_SETTINGS.
        val first = OverlayPermissionModel.decide(
            featureEnabled = true, canDrawOverlays = false, sdkInt = recentSdk,
            rationaleAcknowledged = false,
        )
        val afterAck = OverlayPermissionModel.decide(
            featureEnabled = true, canDrawOverlays = false, sdkInt = recentSdk,
            rationaleAcknowledged = true,
        )
        assertEquals(OverlayPermissionStep.SHOW_RATIONALE, first)
        assertEquals(OverlayPermissionStep.OPEN_OVERLAY_SETTINGS, afterAck)
    }
}
