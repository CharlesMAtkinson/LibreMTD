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

package org.charlesatkinson.libremtd.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.charlesatkinson.libremtd.api.models.BsasPostExpenses
import org.charlesatkinson.libremtd.api.models.BsasPostIncome
import org.charlesatkinson.libremtd.api.models.BsasPostPayload
import org.charlesatkinson.libremtd.api.models.ForeignPropertyBsasExpenses
import org.charlesatkinson.libremtd.api.models.ForeignPropertyBsasIncome
import org.charlesatkinson.libremtd.api.models.ForeignPropertyBsasPostPayload
import org.charlesatkinson.libremtd.api.models.ForeignPropertyBsasPostProperty
import org.charlesatkinson.libremtd.api.models.ForeignPropertyBsasResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * No HTTP and no JavaFX. The two response bodies are condensed from the raw
 * bodies logged from HMRC's sandbox (DYNAMIC_FOREIGN_PROPERTY_PROFIT and
 * FOREIGN_PROPERTY_LOSS): the structure and the field names are exactly as
 * logged, with some repeated detail left out and a few fields HMRC returns
 * which LibreMTD ignores (additions, submissionPeriod) kept in, to prove
 * that they are tolerated.
 */
class BsasClientTest {

    private val profitBody = """
        {"metadata":{"calculationId":"c75dbb53-6237-49e2-b05a-60ef221f0260",
          "requestedDateTime":"2027-04-04T12:34:56Z","adjustedDateTime":"2027-04-05T12:34:56Z",
          "nino":"YX463404B","taxYear":"2026-27","summaryStatus":"valid"},
         "inputs":{"businessId":"XFIS12345678902","businessName":"My Foreign Property Business",
          "accountingPeriodStartDate":"2026-04-06","accountingPeriodEndDate":"2027-04-05","source":"MTD-SA",
          "submissionPeriod":{"submissionId":"617f3a7a-db8e-11e9-8a34-2a2ae2dbeed4",
           "startDate":"2026-04-06","endDate":"2026-07-05","receivedDateTime":"2026-07-07T12:34:56.789Z"}},
         "adjustableSummaryCalculation":{"totalIncome":0.12,
          "income":{"totalRentsReceived":0.12,"premiumsOfLeaseGrant":0.12,"otherPropertyIncome":0.12},
          "totalExpenses":0.12,"netProfit":0.12,
          "additions":{"privateUseAdjustment":0.12,"balancingCharge":0.12},"taxableProfit":2500,
          "countryLevelDetail":[{"countryCode":"CYM","totalIncome":0.12,"taxableProfit":0,
           "propertyLevelDetail":[{"propertyId":"f81d4fae-7dec-11d0-a765-00a0c91e6bf6","totalIncome":5000.99,
            "income":{"totalRentsReceived":5000.99,"premiumsOfLeaseGrant":5000.98,"otherPropertyIncome":5000.97},
            "totalExpenses":4000.5,
            "expenses":{"premisesRunningCosts":1.1,"repairsAndMaintenance":2.2,"financialCosts":3.3,
             "professionalFees":4.4,"costOfServices":5.5,"other":6.6,"travelCosts":7.7},
            "netProfit":5000.99,"netLoss":5000.99,"taxableProfit":5000.99,"adjustedIncomeTaxLoss":5000.99}]}]},
         "adjustments":{"propertyLevelDetail":[{"propertyId":"f81d4fae-7dec-11d0-a765-00a0c91e6bf6",
          "income":{"totalRentsReceived":99999999999.99,"premiumsOfLeaseGrant":99999999999.98,"otherPropertyIncome":99999999999.97},
          "expenses":{"premisesRunningCosts":10.1,"repairsAndMaintenance":10.2,"financialCosts":10.3,
           "professionalFees":10.4,"costOfServices":10.5,"other":10.6,"travelCosts":10.7}}]}}
    """.trimIndent()

    private val lossBody = """
        {"metadata":{"calculationId":"717f3a7a-db8e-11e9-8a34-2a2ae2dbcce4","nino":"AA111111A",
          "taxYear":"2026-27","summaryStatus":"valid"},
         "adjustableSummaryCalculation":{"totalIncome":0.12,"totalExpenses":0.12,"netLoss":0.12,
          "adjustedIncomeTaxLoss":2500,
          "countryLevelDetail":[{"countryCode":"CYM","netLoss":0.12,"adjustedIncomeTaxLoss":0.12,
           "propertyLevelDetail":[{"propertyId":"f81d4fae-7dec-11d0-a765-00a0c91e6bf6","totalIncome":10.0,
            "netLoss":7.5,"adjustedIncomeTaxLoss":7.5}]}]}}
    """.trimIndent()

    private fun foreign(body: String) = json.decodeFromString<ForeignPropertyBsasResponse>(body)

    // ── decoding the foreign retrieve response ────────────────────────────

    @Test
    fun `a profit response decodes with country and property level detail`() {
        val response = foreign(profitBody)

        assertEquals("2026-27", response.metadata.taxYear)
        val country = response.adjustableSummaryCalculation!!.countryLevelDetail!!.single()
        assertEquals("CYM", country.countryCode)
        val property = country.propertyLevelDetail!!.single()
        assertEquals("f81d4fae-7dec-11d0-a765-00a0c91e6bf6", property.propertyId)
        assertEquals(5000.99, property.income?.totalRentsReceived)
        assertEquals(6.6, property.expenses?.other)
        assertEquals(5000.99, property.netProfit)
    }

    @Test
    fun `existing adjustments decode by property id`() {
        val adjustment = foreign(profitBody).adjustments!!.propertyLevelDetail!!.single()

        assertEquals("f81d4fae-7dec-11d0-a765-00a0c91e6bf6", adjustment.propertyId)
        assertEquals(99999999999.99, adjustment.income?.totalRentsReceived)
        assertEquals(10.7, adjustment.expenses?.travelCosts)
    }

    @Test
    fun `a loss response decodes the loss fields and has no profit fields`() {
        val summary = foreign(lossBody).adjustableSummaryCalculation!!

        assertNull(summary.netProfit)
        assertNull(summary.taxableProfit)
        assertEquals(0.12, summary.netLoss)
        assertEquals(2500.0, summary.adjustedIncomeTaxLoss)
        assertEquals(7.5, summary.countryLevelDetail!!.single().propertyLevelDetail!!.single().netLoss)
    }

    @Test
    fun `a response with no adjustments block decodes with null adjustments`() {
        assertNull(foreign(lossBody).adjustments)
    }

    @Test
    fun `a country with no property detail still decodes`() {
        val body = """
            {"metadata":{"calculationId":"x","nino":"AA111111A","taxYear":"2026-27"},
             "adjustableSummaryCalculation":{"countryLevelDetail":[{"countryCode":"FRA"}]}}
        """.trimIndent()

        val country = foreign(body).adjustableSummaryCalculation!!.countryLevelDetail!!.single()

        assertEquals("FRA", country.countryCode)
        assertNull(country.propertyLevelDetail)
    }

    // ── interpreting a response ───────────────────────────────────────────

    @Test
    fun `status 200 with a readable body is found`() {
        val result = interpretBsasResponse(200, profitBody, "foreign property BSAS") {
            json.decodeFromString<ForeignPropertyBsasResponse>(it)
        }

        assertTrue(result is BsasRetrieval.Found)
    }

    @Test
    fun `status 200 with an unreadable body is a failure, not an absent summary`() {
        val result = interpretBsasResponse(200, "{\"unexpected\":true}", "foreign property BSAS") {
            json.decodeFromString<ForeignPropertyBsasResponse>(it)
        }

        assertTrue(result is BsasRetrieval.Failed)
        assertTrue((result as BsasRetrieval.Failed).message.contains("foreign property BSAS"))
    }

    @Test
    fun `status 404 means there is nothing to adjust`() {
        val result = interpretBsasResponse(404, "", "UK property BSAS") { it }

        assertTrue(result is BsasRetrieval.NotAvailable)
    }

    @Test
    fun `other statuses are failures which carry the body`() {
        for (code in listOf(400, 403, 422, 500, 504)) {
            val result = interpretBsasResponse(code, "SOME-HMRC-BODY", "UK property BSAS") { it }

            assertTrue(result is BsasRetrieval.Failed, "HTTP $code")
        }
        val withBody = interpretBsasResponse(422, "SOME-HMRC-BODY", "UK property BSAS") { it }
        assertTrue((withBody as BsasRetrieval.Failed).message.contains("SOME-HMRC-BODY"))
    }

    // ── foreign POST body ─────────────────────────────────────────────────

    private fun jsonObjectOf(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `the foreign POST body is keyed by property id under foreignProperty`() {
        val payload = ForeignPropertyBsasPostPayload(
            listOf(
                ForeignPropertyBsasPostProperty(
                    propertyId = "p-1",
                    income     = ForeignPropertyBsasIncome(totalRentsReceived = 10.5),
                    expenses   = ForeignPropertyBsasExpenses(other = 3.0, travelCosts = -2.0),
                ),
                ForeignPropertyBsasPostProperty(propertyId = "p-2", income = ForeignPropertyBsasIncome(otherPropertyIncome = 1.0)),
            )
        )

        val root = jsonObjectOf(buildForeignPostJson(payload))

        assertEquals(setOf("foreignProperty"), root.keys)
        val foreignProperty = root.getValue("foreignProperty").jsonObject
        assertEquals(setOf("propertyLevelDetail"), foreignProperty.keys)
        val details = foreignProperty.getValue("propertyLevelDetail").jsonArray
        assertEquals(2, details.size)

        val first = details[0].jsonObject
        assertEquals("p-1", first.getValue("propertyId").jsonPrimitive.content)
        assertEquals(10.5, first.getValue("income").jsonObject.getValue("totalRentsReceived").jsonPrimitive.double)
        val expenses = first.getValue("expenses").jsonObject
        assertEquals(setOf("other", "travelCosts"), expenses.keys)
        assertEquals(-2.0, expenses.getValue("travelCosts").jsonPrimitive.double)
    }

    @Test
    fun `the foreign POST body has no country code and omits a null income or expenses object`() {
        val payload = ForeignPropertyBsasPostPayload(
            listOf(ForeignPropertyBsasPostProperty("p-1", income = ForeignPropertyBsasIncome(totalRentsReceived = 1.0)))
        )

        val text = buildForeignPostJson(payload)
        val property = jsonObjectOf(text).getValue("foreignProperty").jsonObject
            .getValue("propertyLevelDetail").jsonArray.single().jsonObject

        assertFalse(text.contains("countryLevelDetail"))
        assertFalse(text.contains("countryCode"))
        assertFalse(property.containsKey("expenses"))
        assertTrue(property.containsKey("income"))
    }

    @Test
    fun `the foreign POST body never contains residentialFinancialCost`() {
        val payload = ForeignPropertyBsasPostPayload(
            listOf(
                ForeignPropertyBsasPostProperty(
                    "p-1",
                    expenses = ForeignPropertyBsasExpenses(
                        premisesRunningCosts = 1.0, repairsAndMaintenance = 1.0, financialCosts = 1.0,
                        professionalFees = 1.0, costOfServices = 1.0, travelCosts = 1.0, other = 1.0,
                    ),
                )
            )
        )

        assertFalse(buildForeignPostJson(payload).contains("residentialFinancialCost"))
    }

    // ── UK POST body ──────────────────────────────────────────────────────

    @Test
    fun `the UK POST body has income and expenses under ukProperty`() {
        val payload = BsasPostPayload.Adjustments(
            income   = BsasPostIncome(totalRentsReceived = 5.0, reversePremiums = 1.5),
            expenses = BsasPostExpenses(other = 3.0),
        )

        val ukProperty = jsonObjectOf(buildUkPostJson(payload)).getValue("ukProperty").jsonObject

        assertEquals(setOf("totalRentsReceived", "reversePremiums"), ukProperty.getValue("income").jsonObject.keys)
        assertEquals(setOf("other"), ukProperty.getValue("expenses").jsonObject.keys)
    }

    @Test
    fun `the UK POST body never contains residentialFinancialCost`() {
        val payload = BsasPostPayload.Adjustments(
            expenses = BsasPostExpenses(
                premisesRunningCosts = 1.0, repairsAndMaintenance = 1.0, financialCosts = 1.0,
                professionalFees = 1.0, costOfServices = 1.0, other = 1.0, travelCosts = 1.0,
            ),
        )

        assertFalse(buildUkPostJson(payload).contains("residentialFinancialCost"))
    }

    @Test
    fun `a zero adjustments payload sends zeroAdjustments true only`() {
        val ukProperty = jsonObjectOf(buildUkPostJson(BsasPostPayload.Zero)).getValue("ukProperty").jsonObject

        assertEquals(setOf("zeroAdjustments"), ukProperty.keys)
        assertNotNull(ukProperty["zeroAdjustments"])
    }
}
