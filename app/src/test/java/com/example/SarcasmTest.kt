package com.example

import com.example.domain.*
import org.junit.Assert.*
import org.junit.Test

class SarcasmTest {
    @Test
    fun extensionTiersEscalateWithoutMissingOrBlankCopy() {
        repeat(20) {
            assertTrue(extensionRemark(0) in SARCASTIC_EXTENSION_L1)
            assertTrue(extensionRemark(1) in SARCASTIC_EXTENSION_L2)
            assertTrue(extensionRemark(2) in SARCASTIC_EXTENSION_L3)
            assertTrue(extensionRemark(10) in SARCASTIC_EXTENSION_L4)
        }
    }

    @Test
    fun buttonCopyStaysCompact() {
        assertTrue((SARCASTIC_START_BUTTONS + SARCASTIC_EXTEND_BUTTONS).all { it.isNotBlank() && it.length <= 24 })
        assertTrue(SARCASTIC_BYPASS.distinct().size >= 6)
    }
}