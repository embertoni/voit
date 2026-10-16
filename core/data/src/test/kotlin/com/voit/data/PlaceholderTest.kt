package com.voit.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceholderTest {
    @Test
    fun moduleMarkerIsStable() {
        assertEquals("core:data", DATA_MODULE)
    }
}
