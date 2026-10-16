package com.voit.platform.android

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure JVM unit tests for [VoicePermissionModel]. No Android Context / SpeechRecognizer /
 * Robolectric involved: every input is a plain value.
 */
class VoicePermissionModelTest {

    private val recentSdk = 34 // Android 14
    private val legacySdk = 30 // Android 11 (below POST_NOTIFICATIONS runtime gate)

    private fun notGranted(
        rationale: Boolean = false,
        permanentlyDenied: Boolean = false,
    ) = PermissionState.NotGranted(
        shouldShowRationale = rationale,
        permanentlyDenied = permanentlyDenied,
    )

    @Test
    fun featureDisabled_doesNothing_forRecordAudio() {
        val step = VoicePermissionModel.decide(
            permission = VoicePermission.RECORD_AUDIO,
            state = notGranted(),
            sdkInt = recentSdk,
            featureEnabled = false,
        )
        assertEquals(VoicePermissionStep.DO_NOTHING_FEATURE_DISABLED, step)
    }

    @Test
    fun featureDisabled_doesNothing_evenWhenGranted() {
        val step = VoicePermissionModel.decide(
            permission = VoicePermission.RECORD_AUDIO,
            state = PermissionState.Granted,
            sdkInt = recentSdk,
            featureEnabled = false,
        )
        assertEquals(VoicePermissionStep.DO_NOTHING_FEATURE_DISABLED, step)
    }

    @Test
    fun enabled_nothingGranted_noRationale_requests() {
        val step = VoicePermissionModel.decide(
            permission = VoicePermission.RECORD_AUDIO,
            state = notGranted(rationale = false),
            sdkInt = recentSdk,
            featureEnabled = true,
        )
        assertEquals(VoicePermissionStep.REQUEST, step)
    }

    @Test
    fun enabled_rationaleFlagSet_showsRationaleBeforeRequest() {
        val step = VoicePermissionModel.decide(
            permission = VoicePermission.RECORD_AUDIO,
            state = notGranted(rationale = true),
            sdkInt = recentSdk,
            featureEnabled = true,
        )
        assertEquals(VoicePermissionStep.SHOW_RATIONALE, step)
    }

    @Test
    fun enabled_granted_proceeds() {
        val step = VoicePermissionModel.decide(
            permission = VoicePermission.RECORD_AUDIO,
            state = PermissionState.Granted,
            sdkInt = recentSdk,
            featureEnabled = true,
        )
        assertEquals(VoicePermissionStep.PROCEED, step)
    }

    @Test
    fun enabled_permanentlyDenied_showsSettingsHint() {
        val step = VoicePermissionModel.decide(
            permission = VoicePermission.RECORD_AUDIO,
            state = notGranted(rationale = false, permanentlyDenied = true),
            sdkInt = recentSdk,
            featureEnabled = true,
        )
        assertEquals(VoicePermissionStep.SHOW_SETTINGS_HINT, step)
    }

    @Test
    fun postNotifications_belowApi33_isSkipped() {
        val step = VoicePermissionModel.decide(
            permission = VoicePermission.POST_NOTIFICATIONS,
            state = notGranted(),
            sdkInt = legacySdk,
            featureEnabled = true,
        )
        assertEquals(VoicePermissionStep.SKIP_NOTIFICATIONS_BELOW_33, step)
    }

    @Test
    fun postNotifications_onApi33_isRequiredAndRequested() {
        val step = VoicePermissionModel.decide(
            permission = VoicePermission.POST_NOTIFICATIONS,
            state = notGranted(),
            sdkInt = API_POST_NOTIFICATIONS,
            featureEnabled = true,
        )
        assertEquals(VoicePermissionStep.REQUEST, step)
    }

    @Test
    fun requiredPermissions_belowApi33_excludesNotifications() {
        assertEquals(
            listOf(VoicePermission.RECORD_AUDIO),
            VoicePermissionModel.requiredPermissions(legacySdk),
        )
    }

    @Test
    fun requiredPermissions_api33AndUp_includesNotifications() {
        assertEquals(
            listOf(VoicePermission.RECORD_AUDIO, VoicePermission.POST_NOTIFICATIONS),
            VoicePermissionModel.requiredPermissions(API_POST_NOTIFICATIONS),
        )
    }

    @Test
    fun flow_disabled_doesNothing() {
        val step = VoicePermissionModel.decideFlow(
            states = emptyMap(),
            sdkInt = recentSdk,
            featureEnabled = false,
        )
        assertEquals(VoicePermissionStep.DO_NOTHING_FEATURE_DISABLED, step)
    }

    @Test
    fun flow_allGranted_proceeds() {
        val step = VoicePermissionModel.decideFlow(
            states = mapOf(
                VoicePermission.RECORD_AUDIO to PermissionState.Granted,
                VoicePermission.POST_NOTIFICATIONS to PermissionState.Granted,
            ),
            sdkInt = recentSdk,
            featureEnabled = true,
        )
        assertEquals(VoicePermissionStep.PROCEED, step)
    }

    @Test
    fun flow_rationaleTakesPriorityOverPlainRequest() {
        val step = VoicePermissionModel.decideFlow(
            states = mapOf(
                VoicePermission.RECORD_AUDIO to notGranted(rationale = true),
                VoicePermission.POST_NOTIFICATIONS to notGranted(rationale = false),
            ),
            sdkInt = recentSdk,
            featureEnabled = true,
        )
        assertEquals(VoicePermissionStep.SHOW_RATIONALE, step)
    }

    @Test
    fun flow_settingsHintTakesHighestPriority() {
        val step = VoicePermissionModel.decideFlow(
            states = mapOf(
                VoicePermission.RECORD_AUDIO to notGranted(permanentlyDenied = true),
                VoicePermission.POST_NOTIFICATIONS to notGranted(rationale = true),
            ),
            sdkInt = recentSdk,
            featureEnabled = true,
        )
        assertEquals(VoicePermissionStep.SHOW_SETTINGS_HINT, step)
    }

    @Test
    fun flow_belowApi33_ignoresNotifications_whenRecordAudioGranted() {
        val step = VoicePermissionModel.decideFlow(
            states = mapOf(
                VoicePermission.RECORD_AUDIO to PermissionState.Granted,
                // A notifications entry even below 33 must be ignored.
                VoicePermission.POST_NOTIFICATIONS to notGranted(rationale = true),
            ),
            sdkInt = legacySdk,
            featureEnabled = true,
        )
        assertEquals(VoicePermissionStep.PROCEED, step)
    }

    @Test
    fun flow_missingStateDefaultsToRequest() {
        val step = VoicePermissionModel.decideFlow(
            states = emptyMap(),
            sdkInt = recentSdk,
            featureEnabled = true,
        )
        assertEquals(VoicePermissionStep.REQUEST, step)
    }
}
