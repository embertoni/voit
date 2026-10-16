package com.voit.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceholderTest {
    @Test
    fun moduleMarkerIsStable() {
        assertEquals("core:domain", DOMAIN_MODULE)
    }
}
