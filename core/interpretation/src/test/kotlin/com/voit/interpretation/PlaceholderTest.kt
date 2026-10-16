package com.voit.interpretation

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceholderTest {
    @Test
    fun moduleMarkerIsStable() {
        assertEquals("core:interpretation", INTERPRETATION_MODULE)
    }
}
