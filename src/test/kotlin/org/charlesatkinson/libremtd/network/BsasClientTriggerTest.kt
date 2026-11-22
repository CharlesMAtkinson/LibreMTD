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

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.charlesatkinson.libremtd.utils.ApiResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * No HTTP. Covers the trigger request body, the business ID pattern, the
 * handling of the trigger response, and the plain-English error messages.
 * HMRC returns nearly all BSAS rule errors with HTTP 400, so most error tests
 * use 400.
 */
class BsasClientTriggerTest {

    private val businessId = "XFIS12345678902"

    private fun root(text: String) = json.parseToJsonElement(text).jsonObject

    private fun failureMessage(result: ApiResult<*>): String {
        assertTrue(result is ApiResult.Failure, "Expected Failure but got $result")
        return (result as ApiResult.Failure).message
    }

    private fun submitMessage(status: Int, code: String): String =
        failureMessage(interpretBsasSubmitResponse(status, """{"code":"$code","message":"x"}""", "BSAS"))

    private fun triggerMessage(status: Int, code: String): String =
        failureMessage(interpretTriggerBsasResponse(status, """{"code":"$code","message":"x"}""", "UK property BSAS"))

    // ── request body ──────────────────────────────────────────────────────

    @Test
    fun `the trigger body has exactly the three required fields`() {
        val body = root(buildTriggerBsasJson(BSAS_TYPE_UK, businessId, "2026-27"))

        assertEquals(setOf("accountingPeriod", "typeOfBusiness", "businessId"), body.keys)
        assertEquals("uk-property", body.getValue("typeOfBusiness").jsonPrimitive.content)
        assertEquals(businessId, body.getValue("businessId").jsonPrimitive.content)
    }

    @Test
    fun `the accounting period is the whole tax year, 6 April to 5 April`() {
        val period = root(buildTriggerBsasJson(BSAS_TYPE_FOREIGN, businessId, "2026-27"))
            .getValue("accountingPeriod").jsonObject

        assertEquals(setOf("startDate", "endDate"), period.keys)
        assertEquals("2026-04-06", period.getValue("startDate").jsonPrimitive.content)
        assertEquals("2027-04-05", period.getValue("endDate").jsonPrimitive.content)
    }

    @Test
    fun `a foreign trigger names foreign-property`() {
        val body = root(buildTriggerBsasJson(BSAS_TYPE_FOREIGN, businessId, "2026-27"))

        assertEquals("foreign-property", body.getValue("typeOfBusiness").jsonPrimitive.content)
    }

    @Test
    fun `an unknown type of business is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            buildTriggerBsasJson("self-employment", businessId, "2026-27")
        }
    }

    // ── business ID ───────────────────────────────────────────────────────

    @Test
    fun `business IDs of HMRC's form are accepted`() {
        assertTrue(BUSINESS_ID_REGEX.matches("XFIS12345678902"))
        assertTrue(BUSINESS_ID_REGEX.matches("XAIS12345678901"))
        assertTrue(BUSINESS_ID_REGEX.matches("X9IS12345678901"))
    }

    @Test
    fun `other business IDs are rejected`() {
        assertFalse(BUSINESS_ID_REGEX.matches(""))
        assertFalse(BUSINESS_ID_REGEX.matches("xfis12345678902"))
        assertFalse(BUSINESS_ID_REGEX.matches("XFIS1234567890"))
        assertFalse(BUSINESS_ID_REGEX.matches("XFIS123456789023"))
        assertFalse(BUSINESS_ID_REGEX.matches("YFIS12345678902"))
    }

    // ── trigger response ──────────────────────────────────────────────────

    @Test
    fun `status 200 gives the calculation id`() {
        val result = interpretTriggerBsasResponse(200, """{"calculationId":"abc-123"}""", "UK property BSAS")

        assertEquals(ApiResult.Success("abc-123"), result)
    }

    @Test
    fun `status 200 ignores fields it does not know`() {
        val result = interpretTriggerBsasResponse(
            200, """{"calculationId":"abc-123","links":[]}""", "UK property BSAS",
        )

        assertEquals(ApiResult.Success("abc-123"), result)
    }

    @Test
    fun `status 200 with an unreadable body is a failure`() {
        val message = failureMessage(interpretTriggerBsasResponse(200, """{"nothing":true}""", "UK property BSAS"))

        assertTrue(message.contains("UK property BSAS"))
        assertTrue(message.contains("could not be read"))
    }

    @Test
    fun `obligations not met is explained in plain words`() {
        val message = triggerMessage(400, "RULE_OBLIGATIONS_NOT_MET")

        assertTrue(message.contains("obligations"))
        assertTrue(message.contains("RULE_OBLIGATIONS_NOT_MET"))
    }

    @Test
    fun `a tax year which has not ended is explained`() {
        val message = triggerMessage(400, "RULE_ACCOUNTING_PERIOD_NOT_ENDED")

        assertTrue(message.contains("has not ended"))
    }

    @Test
    fun `each trigger rule error has its own wording, whether HMRC sends 400 or 422`() {
        val codes = listOf(
            "RULE_OBLIGATIONS_NOT_MET", "RULE_ACCOUNTING_PERIOD_NOT_ENDED", "RULE_NO_ACCOUNTING_PERIOD",
            "RULE_ACCOUNTING_PERIOD_NOT_ALIGNED", "RULE_ACCOUNTING_PERIOD_NOT_SUPPORTED",
            "RULE_TAX_YEAR_NOT_SUPPORTED", "RULE_OUTSIDE_AMENDMENT_WINDOW",
        )
        for (status in listOf(400, 422)) {
            val messages = codes.map { triggerMessage(status, it) }

            assertEquals(codes.size, messages.toSet().size, "HTTP $status")
            codes.forEachIndexed { i, code -> assertTrue(messages[i].contains(code), "HTTP $status $code") }
        }
    }

    @Test
    fun `an unsupported accounting period is explained`() {
        assertTrue(triggerMessage(400, "RULE_ACCOUNTING_PERIOD_NOT_SUPPORTED").contains("does not support"))
    }

    @Test
    fun `an error code inside an errors array is found`() {
        val body = """{"code":"INVALID_REQUEST","message":"x","errors":[{"code":"RULE_NO_ACCOUNTING_PERIOD","message":"y"}]}"""

        assertEquals(listOf("INVALID_REQUEST", "RULE_NO_ACCOUNTING_PERIOD"), hmrcErrorCodes(body))
        assertTrue(failureMessage(interpretTriggerBsasResponse(400, body, "UK property BSAS")).contains("no accounting period"))
    }

    @Test
    fun `an unknown code falls back to a message with the status and the body`() {
        val body = """{"code":"SOMETHING_NEW","message":"x"}"""

        val message = failureMessage(interpretTriggerBsasResponse(422, body, "UK property BSAS"))

        assertTrue(message.contains("SOMETHING_NEW"))
    }

    @Test
    fun `a body which is not JSON gives no codes and a generic failure`() {
        assertTrue(hmrcErrorCodes("<html>Bad gateway</html>").isEmpty())

        val message = failureMessage(interpretTriggerBsasResponse(502, "<html>Bad gateway</html>", "UK property BSAS"))

        assertTrue(message.contains("502"))
    }

    @Test
    fun `status 404 and 403 give failures`() {
        assertTrue(interpretTriggerBsasResponse(404, "", "UK property BSAS") is ApiResult.Failure)
        assertTrue(failureMessage(interpretTriggerBsasResponse(403, "", "UK property BSAS")).contains("Not authorised"))
    }

    // ── submit response ───────────────────────────────────────────────────

    @Test
    fun `a submission accepted with 200 or 204 succeeds`() {
        assertEquals(ApiResult.Success(Unit), interpretBsasSubmitResponse(200, "", "BSAS"))
        assertEquals(ApiResult.Success(Unit), interpretBsasSubmitResponse(204, "", "BSAS"))
    }

    @Test
    fun `already adjusted tells the user to start again, with HTTP 400 as HMRC sends it`() {
        val message = submitMessage(400, "RULE_ALREADY_ADJUSTED")

        assertTrue(message.contains("only be adjusted once"))
        assertTrue(message.contains("start the final declaration again"))
    }

    @Test
    fun `already adjusted is also understood if HMRC sends 422`() {
        assertTrue(submitMessage(422, "RULE_ALREADY_ADJUSTED").contains("only be adjusted once"))
    }

    @Test
    fun `superseded and invalid summaries are explained`() {
        assertTrue(submitMessage(400, "RULE_SUMMARY_STATUS_SUPERSEDED").contains("newer summary"))
        assertTrue(submitMessage(400, "RULE_SUMMARY_STATUS_INVALID").contains("data has changed"))
    }

    @Test
    fun `a resulting negative value is explained in terms of changes, not amounts`() {
        val message = submitMessage(400, "RULE_RESULTING_VALUE_NOT_PERMITTED")

        assertTrue(message.contains("negative"))
        assertTrue(message.contains("changes, not actual amounts"))
    }

    @Test
    fun `zero adjustments errors are explained`() {
        assertTrue(submitMessage(400, "RULE_ZERO_ADJUSTMENTS_INVALID").contains("can only be set to true"))
        assertTrue(submitMessage(400, "RULE_BOTH_ADJUSTMENTS_SUPPLIED").contains("Only one is allowed"))
    }

    @Test
    fun `property income allowance and consolidated expenses errors are explained`() {
        assertTrue(submitMessage(400, "RULE_PROPERTY_INCOME_ALLOWANCE_CLAIMED").contains("property income allowance"))
        assertTrue(submitMessage(400, "RULE_OVER_CONSOLIDATED_EXPENSES_THRESHOLD").contains("consolidated expenses"))
    }

    @Test
    fun `a wrong type of business and an unfulfillable request are explained`() {
        assertTrue(submitMessage(400, "RULE_TYPE_OF_BUSINESS_INCORRECT").contains("different type of business"))
        assertTrue(submitMessage(422, "RULE_REQUEST_CANNOT_BE_FULFILLED").contains("Try again later"))
    }

    @Test
    fun `an empty body error is reported as a LibreMTD fault`() {
        assertTrue(submitMessage(400, "RULE_INCORRECT_OR_EMPTY_BODY_SUBMITTED").contains("fault in LibreMTD"))
    }

    @Test
    fun `property id errors are explained`() {
        assertTrue(submitMessage(400, "RULE_PROPERTY_ID_MISMATCH").contains("does not belong"))
        assertTrue(submitMessage(400, "RULE_DUPLICATE_PROPERTY_ID").contains("more than once"))
    }

    @Test
    fun `every submit error has its own wording`() {
        val codes = listOf(
            "RULE_ALREADY_ADJUSTED", "RULE_SUMMARY_STATUS_SUPERSEDED", "RULE_SUMMARY_STATUS_INVALID",
            "RULE_RESULTING_VALUE_NOT_PERMITTED", "RULE_ZERO_ADJUSTMENTS_INVALID",
            "RULE_BOTH_ADJUSTMENTS_SUPPLIED", "RULE_PROPERTY_INCOME_ALLOWANCE_CLAIMED",
            "RULE_OVER_CONSOLIDATED_EXPENSES_THRESHOLD", "RULE_TYPE_OF_BUSINESS_INCORRECT",
            "RULE_PROPERTY_ID_MISMATCH", "RULE_DUPLICATE_PROPERTY_ID", "RULE_OUTSIDE_AMENDMENT_WINDOW",
        )
        val messages = codes.map { submitMessage(400, it) }

        assertEquals(codes.size, messages.toSet().size)
    }

    @Test
    fun `an unknown submit error keeps HMRC's body`() {
        val message = failureMessage(interpretBsasSubmitResponse(400, "ODD-BODY", "BSAS"))

        assertTrue(message.contains("ODD-BODY"))
    }
}
