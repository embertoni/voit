package com.voit.actions

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceholderTest {
    @Test
    fun moduleMarkerIsStable() {
        assertEquals("core:actions", ACTIONS_MODULE)
    }
}
