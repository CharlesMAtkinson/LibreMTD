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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

// ── Request model ─────────────────────────────────────────────────────────────

@Serializable
data class ForeignPropertyCumulativeRequest(
    val fromDate:        String,
    val toDate:          String,
    val foreignProperty: List<ForeignPropertyItem>,
)

/**
 * One entry in the foreignProperty array, identified by HMRC's propertyId.
 *
 * Earlier HMRC API versions identified foreign properties in this endpoint
 * by countryCode instead, for tax years up to 2025-26 — which is why this
 * field used to be optional and share the model with a countryCode field.
 * LibreMTD no longer supports tax years before 2026-27 (see
 * database.availableTaxYears), so propertyId is now always present and the
 * countryCode alternative has been removed rather than left unused.
 */
@Serializable
data class ForeignPropertyItem(
    val propertyId: String,
    val income:     ForeignPropertyIncomeBody?   = null,
    val expenses:   ForeignPropertyExpensesBody? = null,
)

@Serializable
data class ForeignPropertyIncomeBody(
    val rentIncome:                       ForeignRentIncomeBody,
    val foreignTaxCreditRelief:           Boolean,
    val premiumsOfLeaseGrant:             Double? = null,
    val otherPropertyIncome:              Double? = null,
    val foreignTaxPaidOrDeducted:         Double? = null,
    val specialWithholdingTaxOrUkTaxPaid: Double? = null,
)

@Serializable
data class ForeignRentIncomeBody(
    val rentAmount: Double,
)

@Serializable
data class ForeignPropertyExpensesBody(
    val premisesRunningCosts:                Double? = null,
    val repairsAndMaintenance:                Double? = null,
    val financialCosts:                       Double? = null,
    val professionalFees:                     Double? = null,
    val travelCosts:                          Double? = null,
    val costOfServices:                       Double? = null,
    val residentialFinancialCost:             Double? = null,
    val broughtFwdResidentialFinancialCost:   Double? = null,
    val other:                                Double? = null,
)

// ── Client ────────────────────────────────────────────────────────────────────

class PropertyForeignSubmissionClient(private val apiClient: HmrcApiClient) {

    /**
     * Submits a cumulative foreign property period summary.
     * Uses PUT — idempotent, replaces any previous submission for the tax year.
     *
     * [fromDate] is always the tax year start, e.g. "2026-04-06".
     * [toDate]   is the end of the latest quarter being reported, e.g. "2026-07-05".
     *
     * Note: HMRC's documentation for this endpoint lists 400, 403 and 404 as
     * response codes but no 2xx — 204 is assumed by analogy with the UK
     * cumulative endpoint (PUT-to-replace, empty body) and should be
     * confirmed against the sandbox.
     *
     * @param nino            User's National Insurance number
     * @param businessId      HMRC business ID for the foreign property business
     * @param taxYear         Format "2026-27"
     * @param fromDate        Tax year start date, format "YYYY-MM-DD"
     * @param toDate          Quarter end date, format "YYYY-MM-DD"
     * @param foreignProperty One entry per foreign property being reported —
     *                        see [ForeignPropertyItem]
     */
    suspend fun submitCumulative(
        nino:            String,
        businessId:      String,
        taxYear:         String,
        fromDate:        String,
        toDate:          String,
        foreignProperty: List<ForeignPropertyItem>,
        context:         ClientContext,
    ): SubmissionResult = withContext(Dispatchers.IO) {
        val path = "/individuals/business/property/foreign/$nino/$businessId/cumulative/$taxYear"

        val requestBody = ForeignPropertyCumulativeRequest(
            fromDate        = fromDate,
            toDate          = toDate,
            foreignProperty = foreignProperty,
        )

        val bodyJson = json.encodeToString(requestBody)
        logger.info { "Submitting cumulative foreign property update to $path" }
        logger.info { "Request body: $bodyJson" }

        val response = apiClient.put(
            path    = path,
            body    = bodyJson,
            context = context,
            version = "6.0",
        )

        if (response == null) {
            return@withContext SubmissionResult(
                success    = false,
                statusCode = 0,
                message    = "Network error — no response received",
            )
        }

        when (response.statusCode()) {
            204  -> SubmissionResult(true, 204, "Submission accepted by HMRC ✓")
            400  -> {
                val message = try {
                    json.decodeFromString<HmrcErrorBody>(response.body()).message
                        ?: response.body()
                } catch (e: Exception) {
                    response.body()
                }
                SubmissionResult(false, 400, "Invalid request: $message")
            }
            401  -> SubmissionResult(false, 401, "Unauthorised — reconnect to HMRC")
            403  -> SubmissionResult(false, 403, "Forbidden — check your credentials")
            404  -> SubmissionResult(false, 404, "Not found — check the log for details")
            else -> SubmissionResult(false, response.statusCode(), "Unexpected response: ${response.body()}")
        }
    }
}
