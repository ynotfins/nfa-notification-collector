package com.nfaalerts.collector.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RawTextSelectorTest {
    @Test
    fun `candidate code points and line boundaries are preserved exactly`() {
        val bigText = "  FIRE\r\nCaf\u00E9 \uD83D\uDE92  "
        val lines = listOf(" alpha ", "beta\r\n", "\uD83D\uDE80")

        val result =
            RawTextSelector.select(
                orderedFields = RawTextField.DEFAULT_ORDER,
                candidates =
                    mapOf(
                        RawTextField.BIG_TEXT to listOf(bigText),
                        RawTextField.TEXT_LINES to lines,
                    ),
            )

        assertEquals(bigText, result.rawText)
        assertEquals(listOf(bigText), result.candidates[RawTextField.BIG_TEXT])
        assertEquals(lines, result.candidates[RawTextField.TEXT_LINES])
    }

    @Test
    fun `ordered first present candidate wins without normalization`() {
        val result =
            RawTextSelector.select(
                orderedFields = listOf(RawTextField.TEXT_LINES, RawTextField.TEXT),
                candidates =
                    mapOf(
                        RawTextField.TEXT to listOf("fallback"),
                        RawTextField.TEXT_LINES to listOf("one ", " two"),
                    ),
            )

        assertEquals("one ", result.rawText)
        assertEquals(RawTextField.TEXT_LINES, result.selectedField)
    }

    @Test
    fun `missing candidates produce null raw text and preserve empty values`() {
        val result =
            RawTextSelector.select(
                orderedFields = RawTextField.DEFAULT_ORDER,
                candidates = mapOf(RawTextField.TEXT to emptyList()),
            )

        assertNull(result.rawText)
        assertEquals(emptyList<String>(), result.candidates[RawTextField.TEXT])
    }
}
