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
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; explicitNulls = false }

// ── Request model ─────────────────────────────────────────────────────────────

@Serializable
data class ForeignPropertyCumulativeRequest(
    val fromDate:        String,
    val toDate:          String,
    val foreignProperty: List<ForeignPropertyItem>,
)

/**
 * One entry in the foreignProperty array.
 * Exactly one of [countryCode] (tax years up to 2025-26) or [propertyId]
 * (tax years 2026-27 onward) should be set — never both, never neither.
 * Deciding which applies for a given taxYear is the caller's responsibility,
 * not this client's.
 */
@Serializable
data class ForeignPropertyItem(
    val countryCode: String? = null,
    val propertyId:  String? = null,
    val income:      ForeignPropertyIncomeBody?   = null,
    val expenses:    ForeignPropertyExpensesBody? = null,
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
     * Submits a cumulative foreign property period summary for 2025-26 onwards.
     * Uses PUT — idempotent, replaces any previous submission for the tax year.
     *
     * [fromDate] is always the tax year start, e.g. "2025-04-06".
     * [toDate]   is the end of the latest quarter being reported, e.g. "2025-07-05".
     *
     * Note: HMRC's documentation for this endpoint lists 400, 403 and 404 as
     * response codes but no 2xx — 204 is assumed by analogy with the UK
     * cumulative endpoint (PUT-to-replace, empty body) and should be
     * confirmed against the sandbox.
     *
     * @param nino            User's National Insurance number
     * @param businessId      HMRC business ID for the foreign property business
     * @param taxYear         Format "2025-26"
     * @param fromDate        Tax year start date, format "YYYY-MM-DD"
     * @param toDate          Quarter end date, format "YYYY-MM-DD"
     * @param foreignProperty One entry per foreign property being reported —
     *                        see [ForeignPropertyItem] for the countryCode/
     *                        propertyId split by tax year
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
