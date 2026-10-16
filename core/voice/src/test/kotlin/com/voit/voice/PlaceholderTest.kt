package com.voit.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceholderTest {
    @Test
    fun moduleMarkerIsStable() {
        assertEquals("core:voice", VOICE_MODULE)
    }
}
