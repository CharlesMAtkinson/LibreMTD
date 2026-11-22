/*
 *
 *  * Copyright (C) 2026 Charles Michael Atkinson
 *  *
 *  * This program is free software: you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation, either version 3 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package org.charlesatkinson.libremtd.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure functions only: no JavaFX toolkit and no database.
 */
class BsasAdjustmentMessageTest {

    private fun input(current: String) = AdjustmentInput("", current)

    private fun invalidUk(income: Map<String, AdjustmentInput>, expenses: Map<String, AdjustmentInput> = emptyMap()) =
        buildUkPostPayload(income, expenses) as BuiltPayload.Invalid

    // ── typed zero ────────────────────────────────────────────────────────

    @Test
    fun `isTypedZero recognises every way of writing zero`() {
        for (text in listOf("0", "0.00", " 0 ", "-0", "0.0", "0,0")) {
            assertTrue(isTypedZero(text), "text: \"$text\"")
        }
    }

    @Test
    fun `isTypedZero is false for other text`() {
        for (text in listOf("", "abc", "0.01", "10", "-5")) {
            assertFalse(isTypedZero(text), "text: \"$text\"")
        }
    }

    @Test
    fun `a typed zero is listed as bad and as a zero field`() {
        val invalid = invalidUk(mapOf("totalRentsReceived" to input("0")))

        assertEquals(listOf("income.totalRentsReceived"), invalid.badFields)
        assertEquals(listOf("income.totalRentsReceived"), invalid.zeroFields)
    }

    @Test
    fun `other invalid text is bad but not a zero field`() {
        val invalid = invalidUk(mapOf("totalRentsReceived" to input("abc")))

        assertEquals(listOf("income.totalRentsReceived"), invalid.badFields)
        assertTrue(invalid.zeroFields.isEmpty())
    }

    @Test
    fun `a foreign typed zero names the property and the field`() {
        val result = buildForeignPostPayload(
            listOf(PropertyAdjustmentInputs("p-1", emptyMap(), mapOf("other" to input("0.00"))))
        ) as BuiltPayload.Invalid

        assertEquals(listOf("p-1: expenses.other"), result.zeroFields)
    }

    // ── the message ───────────────────────────────────────────────────────

    @Test
    fun `the message for a typed zero mentions zero and tells the user to clear the field`() {
        val message = invalidAdjustmentMessage(invalidUk(mapOf("totalRentsReceived" to input("0"))))

        assertTrue(message.contains("zero"))
        assertTrue(message.contains("clear the field"))
        assertFalse(message.contains("decimal places"), message)
    }

    @Test
    fun `the message for other invalid text talks about numbers, not zero`() {
        val message = invalidAdjustmentMessage(invalidUk(mapOf("totalRentsReceived" to input("1.234"))))

        assertTrue(message.contains("decimal places"))
        assertFalse(message.contains("zero"))
    }

    @Test
    fun `the message covers both problems when both are present`() {
        val message = invalidAdjustmentMessage(
            invalidUk(mapOf("totalRentsReceived" to input("0")), mapOf("other" to input("abc")))
        )

        assertTrue(message.contains("zero"))
        assertTrue(message.contains("decimal places"))
    }

    // ── hint text ─────────────────────────────────────────────────────────

    @Test
    fun `the hint no longer says a blank field is sent as zero`() {
        val text = bsasHintText("UK property")

        assertFalse(text.contains("sent to HMRC as zero"), text)
        assertFalse(text.contains("as zero"), text)
    }

    @Test
    fun `the hint says a new submission replaces an earlier one and a blank field means no adjustment`() {
        val text = bsasHintText("foreign property")

        assertTrue(text.contains("replaces any earlier adjustment"))
        assertTrue(text.contains("blank means no adjustment") || text.contains("left blank means no adjustment"))
    }

    @Test
    fun `the hint says HMRC does not accept zero`() {
        assertTrue(bsasHintText("UK property").contains("HMRC does not accept an adjustment of 0"))
    }

    @Test
    fun `the hint names the property type it is given`() {
        assertTrue(bsasHintText("foreign property").contains("your foreign property submissions"))
        assertTrue(bsasHintText("UK property").contains("your UK property submissions"))
    }
}
