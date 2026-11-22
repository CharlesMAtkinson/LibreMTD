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

import org.charlesatkinson.libremtd.api.models.BsasMetadata
import org.charlesatkinson.libremtd.api.models.BsasPostPayload
import org.charlesatkinson.libremtd.api.models.ForeignPropertyBsasAdjustments
import org.charlesatkinson.libremtd.api.models.ForeignPropertyBsasCountryCalculation
import org.charlesatkinson.libremtd.api.models.ForeignPropertyBsasExpenses
import org.charlesatkinson.libremtd.api.models.ForeignPropertyBsasIncome
import org.charlesatkinson.libremtd.api.models.ForeignPropertyBsasPropertyAdjustment
import org.charlesatkinson.libremtd.api.models.ForeignPropertyBsasPropertyCalculation
import org.charlesatkinson.libremtd.api.models.ForeignPropertyBsasResponse
import org.charlesatkinson.libremtd.api.models.ForeignPropertyBsasSummaryCalculation
import org.charlesatkinson.libremtd.database.Property
import org.charlesatkinson.libremtd.database.PropertyType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure functions only, so no JavaFX toolkit and no database are needed.
 */
class BsasAdjustmentsTest {

    private fun input(initial: String, current: String) = AdjustmentInput(initial, current)

    // ── resolveAdjustment ─────────────────────────────────────────────────

    @Test
    fun `sending zero for a cleared field is switched off because HMRC rejects zero`() {
        // Confirmed in the HMRC sandbox on 31 October 2026: FORMAT_VALUE,
        // "cannot be 0 or 0.00". If this test fails, someone has switched
        // the behaviour back on: check the sandbox again first.
        assertFalse(SEND_ZERO_FOR_CLEARED_FIELD)
    }

    @Test
    fun `a field which was blank and still is blank is omitted`() {
        assertEquals(AdjustmentValue.Omit, resolveAdjustment(input("", "")))
        assertEquals(AdjustmentValue.Omit, resolveAdjustment(input("", "   ")))
    }

    @Test
    fun `a cleared field which had a prior value is omitted by default`() {
        assertEquals(AdjustmentValue.Omit, resolveAdjustment(input("50.00", "")))
    }

    @Test
    fun `a cleared field which had a prior value is sent as zero when sending zero is switched on`() {
        assertEquals(AdjustmentValue.Send(0.0), resolveAdjustment(input("50.00", ""), sendZeroForCleared = true))
    }

    @Test
    fun `a typed amount is sent, with surrounding spaces and thousands separators ignored`() {
        assertEquals(AdjustmentValue.Send(12.5), resolveAdjustment(input("", " 12.5 ")))
        assertEquals(AdjustmentValue.Send(1250.5), resolveAdjustment(input("", "1,250.50")))
    }

    @Test
    fun `a negative adjustment is allowed`() {
        assertEquals(AdjustmentValue.Send(-50.0), resolveAdjustment(input("", "-50")))
    }

    @Test
    fun `a typed zero is invalid because HMRC rejects it`() {
        for (text in listOf("0", "0.00", " 0 ", "-0", "0.0")) {
            assertEquals(AdjustmentValue.Invalid, resolveAdjustment(input("", text)), "text: \"$text\"")
        }
    }

    @Test
    fun `text which is not a number is invalid`() {
        assertEquals(AdjustmentValue.Invalid, resolveAdjustment(input("", "abc")))
    }

    @Test
    fun `more than two decimal places is invalid but trailing zeros are fine`() {
        assertEquals(AdjustmentValue.Invalid, resolveAdjustment(input("", "10.123")))
        assertEquals(AdjustmentValue.Send(10.1), resolveAdjustment(input("", "10.100")))
    }

    @Test
    fun `a value outside HMRC's range is invalid and the limits themselves are valid`() {
        assertEquals(AdjustmentValue.Invalid, resolveAdjustment(input("", "100000000000")))
        assertEquals(AdjustmentValue.Invalid, resolveAdjustment(input("", "-100000000000")))
        assertEquals(AdjustmentValue.Send(99999999999.99), resolveAdjustment(input("", "99999999999.99")))
    }

    // ── formatAdjustment ──────────────────────────────────────────────────

    @Test
    fun `formatAdjustment gives two decimal places, and blank for null`() {
        assertEquals("", formatAdjustment(null))
        assertEquals("50.00", formatAdjustment(50.0))
        assertEquals("0.12", formatAdjustment(0.12))
        assertEquals("99999999999.99", formatAdjustment(99999999999.99))
        assertEquals("-2.50", formatAdjustment(-2.5))
    }

    // ── UK payload ────────────────────────────────────────────────────────

    @Test
    fun `a UK payload carries typed fields and omits untouched ones`() {
        val result = buildUkPostPayload(
            income   = mapOf("totalRentsReceived" to input("", "10"), "reversePremiums" to input("", "")),
            expenses = mapOf("other" to input("", "3.5")),
        )

        val payload = (result as BuiltPayload.Ready).payload as BsasPostPayload.Adjustments
        assertEquals(10.0, payload.income?.totalRentsReceived)
        assertNull(payload.income?.reversePremiums)
        assertEquals(3.5, payload.expenses?.other)
    }

    @Test
    fun `a UK payload leaves out a cleared field which had a value`() {
        val result = buildUkPostPayload(
            income   = emptyMap(),
            expenses = mapOf("premisesRunningCosts" to input("5.00", "")),
        )

        val payload = (result as BuiltPayload.Ready).payload as BsasPostPayload.Adjustments
        assertNull(payload.income)
        assertNull(payload.expenses)
    }

    @Test
    fun `a UK payload sends zero for a cleared field only when sending zero is switched on`() {
        val result = buildUkPostPayload(
            income   = emptyMap(),
            expenses = mapOf("premisesRunningCosts" to input("5.00", "")),
            sendZeroForCleared = true,
        )

        val payload = (result as BuiltPayload.Ready).payload as BsasPostPayload.Adjustments
        assertEquals(0.0, payload.expenses?.premisesRunningCosts)
    }

    @Test
    fun `a UK payload with nothing entered has neither income nor expenses`() {
        val result = buildUkPostPayload(
            income   = mapOf("totalRentsReceived" to input("", "")),
            expenses = mapOf("other" to input("", "")),
        )

        val payload = (result as BuiltPayload.Ready).payload as BsasPostPayload.Adjustments
        assertNull(payload.income)
        assertNull(payload.expenses)
    }

    @Test
    fun `a UK payload with an invalid field names it`() {
        val result = buildUkPostPayload(
            income   = mapOf("totalRentsReceived" to input("", "x")),
            expenses = mapOf("other" to input("", "1")),
        )

        assertEquals(listOf("income.totalRentsReceived"), (result as BuiltPayload.Invalid).badFields)
    }

    @Test
    fun `a UK payload with a typed zero is invalid`() {
        val result = buildUkPostPayload(
            income   = mapOf("totalRentsReceived" to input("", "0")),
            expenses = emptyMap(),
        )

        assertEquals(listOf("income.totalRentsReceived"), (result as BuiltPayload.Invalid).badFields)
    }

    @Test
    fun `the UK field lists no longer offer residential financial cost`() {
        assertFalse(ADJUSTMENT_EXPENSE_FIELDS.any { it.key.contains("residential", ignoreCase = true) })
        assertEquals(
            listOf("totalRentsReceived", "premiumsOfLeaseGrant", "reversePremiums", "otherPropertyIncome"),
            UK_INCOME_ADJUSTMENT_FIELDS.map { it.key },
        )
    }

    // ── foreign payload ───────────────────────────────────────────────────

    @Test
    fun `a foreign payload has one entry per property with something to send`() {
        val result = buildForeignPostPayload(
            listOf(
                PropertyAdjustmentInputs(
                    "p-1",
                    income   = mapOf("totalRentsReceived" to input("", "100")),
                    expenses = mapOf("other" to input("", "")),
                ),
                PropertyAdjustmentInputs(
                    "p-2",
                    income   = mapOf("totalRentsReceived" to input("", "")),
                    expenses = mapOf("other" to input("", "")),
                ),
            )
        )

        val payload = (result as BuiltPayload.Ready).payload
        assertEquals(listOf("p-1"), payload.propertyLevelDetail.map { it.propertyId })
        assertEquals(100.0, payload.propertyLevelDetail.single().income?.totalRentsReceived)
        assertNull(payload.propertyLevelDetail.single().expenses)
    }

    @Test
    fun `two properties in the same country are separate entries`() {
        val result = buildForeignPostPayload(
            listOf(
                PropertyAdjustmentInputs("p-1", mapOf("totalRentsReceived" to input("", "1")), emptyMap()),
                PropertyAdjustmentInputs("p-2", mapOf("totalRentsReceived" to input("", "2")), emptyMap()),
            )
        )

        val details = (result as BuiltPayload.Ready).payload.propertyLevelDetail
        assertEquals(listOf("p-1", "p-2"), details.map { it.propertyId })
        assertEquals(listOf(1.0, 2.0), details.map { it.income?.totalRentsReceived })
    }

    @Test
    fun `a foreign cleared field with a prior value is left out and a never filled field is omitted`() {
        val result = buildForeignPostPayload(
            listOf(
                PropertyAdjustmentInputs(
                    "p-1",
                    income   = mapOf(
                        "totalRentsReceived"   to input("20.00", ""),
                        "premiumsOfLeaseGrant" to input("", ""),
                        "otherPropertyIncome"  to input("", "3"),
                    ),
                    expenses = emptyMap(),
                )
            )
        )

        val income = (result as BuiltPayload.Ready).payload.propertyLevelDetail.single().income
        assertNull(income?.totalRentsReceived)
        assertNull(income?.premiumsOfLeaseGrant)
        assertEquals(3.0, income?.otherPropertyIncome)
    }

    @Test
    fun `a property whose only change is a cleared field is left out of the payload`() {
        val result = buildForeignPostPayload(
            listOf(PropertyAdjustmentInputs("p-1", mapOf("totalRentsReceived" to input("20.00", "")), emptyMap()))
        )

        assertTrue((result as BuiltPayload.Ready).payload.propertyLevelDetail.isEmpty())
    }

    @Test
    fun `a foreign cleared field is sent as zero only when sending zero is switched on`() {
        val result = buildForeignPostPayload(
            listOf(PropertyAdjustmentInputs("p-1", mapOf("totalRentsReceived" to input("20.00", "")), emptyMap())),
            sendZeroForCleared = true,
        )

        val income = (result as BuiltPayload.Ready).payload.propertyLevelDetail.single().income
        assertEquals(0.0, income?.totalRentsReceived)
    }

    @Test
    fun `a foreign payload with no properties to adjust is empty`() {
        val result = buildForeignPostPayload(emptyList())

        assertTrue((result as BuiltPayload.Ready).payload.propertyLevelDetail.isEmpty())
    }

    @Test
    fun `a foreign payload with invalid fields names the property and the field`() {
        val result = buildForeignPostPayload(
            listOf(
                PropertyAdjustmentInputs("p-1", mapOf("totalRentsReceived" to input("", "1")), emptyMap()),
                PropertyAdjustmentInputs("p-2", emptyMap(), mapOf("other" to input("", "1.234"))),
            )
        )

        assertEquals(listOf("p-2: expenses.other"), (result as BuiltPayload.Invalid).badFields)
    }

    @Test
    fun `a foreign payload with a typed zero names the property and the field`() {
        val result = buildForeignPostPayload(
            listOf(PropertyAdjustmentInputs("p-1", mapOf("totalRentsReceived" to input("99999999999.99", "0")), emptyMap()))
        )

        assertEquals(listOf("p-1: income.totalRentsReceived"), (result as BuiltPayload.Invalid).badFields)
    }

    // ── property blocks ───────────────────────────────────────────────────

    private fun localForeign(address: String, code: String, hmrcId: String?, endedAt: String? = null) = Property(
        id = 1, userId = 1, address = address, postcode = null, propertyType = PropertyType.FOREIGN,
        countryCode = code, hmrcPropertyId = hmrcId, hmrcRegisteredAt = null, hmrcRegisteredTaxYear = null,
        createdAt = "2026-04-06T00:00:00", endedAt = endedAt, supersededAt = null,
    )

    private fun metadata() = BsasMetadata(calculationId = "c", nino = "AA111111A", taxYear = "2026-27")

    private fun propertyCalc(id: String) =
        ForeignPropertyBsasPropertyCalculation(propertyId = id, totalIncome = 10.0, netProfit = 4.0)

    private fun response(
        countries: List<ForeignPropertyBsasCountryCalculation>,
        adjustments: List<ForeignPropertyBsasPropertyAdjustment>? = null,
    ) = ForeignPropertyBsasResponse(
        metadata = metadata(),
        adjustableSummaryCalculation = ForeignPropertyBsasSummaryCalculation(countryLevelDetail = countries),
        adjustments = adjustments?.let { ForeignPropertyBsasAdjustments(it) },
    )

    @Test
    fun `a property is labelled from the local property with the matching HMRC id`() {
        val local = localForeign("10 Rue de Paris", "FRA", "p-1")
        val blocks = buildForeignBsasBlocks(
            response(listOf(ForeignPropertyBsasCountryCalculation("FRA", propertyLevelDetail = listOf(propertyCalc("p-1"))))),
            listOf(local),
        )

        val block = blocks.single()
        assertEquals("10 Rue de Paris (FRA)", block.label)
        assertTrue(block.matchedLocalProperty)
        assertEquals("FRA", block.countryCode)
        assertEquals("p-1", block.propertyId)
    }

    @Test
    fun `an ended local property is marked as ended in its label`() {
        val local = localForeign("10 Rue de Paris", "FRA", "p-1", endedAt = "2026-08-01T00:00:00")
        val blocks = buildForeignBsasBlocks(
            response(listOf(ForeignPropertyBsasCountryCalculation("FRA", propertyLevelDetail = listOf(propertyCalc("p-1"))))),
            listOf(local),
        )

        assertEquals("10 Rue de Paris (FRA) (ended)", blocks.single().label)
    }

    @Test
    fun `a property with no local match is still listed, with its HMRC id`() {
        val blocks = buildForeignBsasBlocks(
            response(listOf(ForeignPropertyBsasCountryCalculation("CYM", propertyLevelDetail = listOf(propertyCalc("unknown-id"))))),
            listOf(localForeign("10 Rue de Paris", "FRA", "p-1")),
        )

        val block = blocks.single()
        assertFalse(block.matchedLocalProperty)
        assertTrue(block.label.contains("unknown-id"))
        assertEquals("CYM", block.countryCode)
    }

    @Test
    fun `a local property which has no HMRC id is never matched`() {
        val blocks = buildForeignBsasBlocks(
            response(listOf(ForeignPropertyBsasCountryCalculation("FRA", propertyLevelDetail = listOf(propertyCalc("p-1"))))),
            listOf(localForeign("10 Rue de Paris", "FRA", null)),
        )

        assertFalse(blocks.single().matchedLocalProperty)
    }

    @Test
    fun `two properties in one country give two blocks in the order HMRC reported them`() {
        val blocks = buildForeignBsasBlocks(
            response(
                listOf(
                    ForeignPropertyBsasCountryCalculation(
                        "FRA", propertyLevelDetail = listOf(propertyCalc("p-1"), propertyCalc("p-2")),
                    )
                )
            ),
            emptyList(),
        )

        assertEquals(listOf("p-1", "p-2"), blocks.map { it.propertyId })
        assertEquals(listOf("FRA", "FRA"), blocks.map { it.countryCode })
    }

    @Test
    fun `existing adjustments prefill the matching property only`() {
        val blocks = buildForeignBsasBlocks(
            response(
                listOf(
                    ForeignPropertyBsasCountryCalculation(
                        "FRA", propertyLevelDetail = listOf(propertyCalc("p-1"), propertyCalc("p-2")),
                    )
                ),
                adjustments = listOf(
                    ForeignPropertyBsasPropertyAdjustment(
                        "p-2",
                        income   = ForeignPropertyBsasIncome(totalRentsReceived = -50.0),
                        expenses = ForeignPropertyBsasExpenses(other = 7.0),
                    )
                ),
            ),
            emptyList(),
        )

        val first = blocks.first { it.propertyId == "p-1" }
        val second = blocks.first { it.propertyId == "p-2" }
        assertNull(first.existingIncome["totalRentsReceived"])
        assertEquals(-50.0, second.existingIncome["totalRentsReceived"])
        assertEquals(7.0, second.existingExpenses["other"])
    }

    @Test
    fun `an adjustment for a property missing from the summary is not hidden`() {
        val local = localForeign("10 Rue de Paris", "FRA", "p-9")
        val blocks = buildForeignBsasBlocks(
            response(
                listOf(ForeignPropertyBsasCountryCalculation("FRA", propertyLevelDetail = listOf(propertyCalc("p-1")))),
                adjustments = listOf(
                    ForeignPropertyBsasPropertyAdjustment("p-9", income = ForeignPropertyBsasIncome(totalRentsReceived = 5.0))
                ),
            ),
            listOf(local),
        )

        assertEquals(listOf("p-1", "p-9"), blocks.map { it.propertyId })
        val extra = blocks.last()
        assertEquals("FRA", extra.countryCode)
        assertEquals("10 Rue de Paris (FRA)", extra.label)
        assertNull(extra.summary)
        assertEquals(5.0, extra.existingIncome["totalRentsReceived"])
    }

    @Test
    fun `a response with no properties gives no blocks`() {
        val blocks = buildForeignBsasBlocks(
            ForeignPropertyBsasResponse(metadata = metadata()),
            listOf(localForeign("10 Rue de Paris", "FRA", "p-1")),
        )

        assertTrue(blocks.isEmpty())
    }

    @Test
    fun `the foreign field lists match the fields HMRC accepts`() {
        assertEquals(
            listOf("totalRentsReceived", "premiumsOfLeaseGrant", "otherPropertyIncome"),
            FOREIGN_INCOME_ADJUSTMENT_FIELDS.map { it.key },
        )
        assertEquals(
            listOf(
                "premisesRunningCosts", "repairsAndMaintenance", "financialCosts", "professionalFees",
                "costOfServices", "travelCosts", "other",
            ),
            ADJUSTMENT_EXPENSE_FIELDS.map { it.key },
        )
    }

    // ── profit and loss figures ───────────────────────────────────────────

    @Test
    fun `a profit result is labelled as a profit`() {
        assertEquals(LabelledFigure("Net profit", 4.0), netResult(netProfit = 4.0, netLoss = null))
        assertEquals(LabelledFigure("Taxable profit", 9.0), taxResult(taxableProfit = 9.0, adjustedIncomeTaxLoss = null))
    }

    @Test
    fun `a loss result is labelled as a loss`() {
        assertEquals(LabelledFigure("Net loss", 3.0), netResult(netProfit = null, netLoss = 3.0))
        assertEquals(
            LabelledFigure("Adjusted income tax loss", 8.0),
            taxResult(taxableProfit = null, adjustedIncomeTaxLoss = 8.0),
        )
    }

    @Test
    fun `when a profit figure is present it wins over a loss figure`() {
        // HMRC's static sandbox data returns both at property level.
        assertEquals(LabelledFigure("Net profit", 5.0), netResult(netProfit = 5.0, netLoss = 6.0))
    }

    @Test
    fun `with neither figure the profit label is used with no amount`() {
        assertEquals(LabelledFigure("Net profit", null), netResult(null, null))
        assertEquals(LabelledFigure("Taxable profit", null), taxResult(null, null))
    }
}
