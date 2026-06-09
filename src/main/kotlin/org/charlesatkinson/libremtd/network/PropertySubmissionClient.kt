/*
 * Copyright (C) 2026 Charles Michael Atkinson
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package org.charlesatkinson.libremtd.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

// Opt-in added 22 May 2026 to suppress compiler warning from explicitNulls = false:
// "This declaration needs opt-in. Its usage should be marked with
// '@kotlinx.serialization.ExperimentalSerializationApi' or
// '@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)'"
// when using Kotlin 2.0.21 and org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; explicitNulls = false }

// ── Request model ─────────────────────────────────────────────────────────────

@Serializable
data class UkPropertyCumulativeRequest(
    val fromDate:   String,
    val toDate:     String,
    val ukProperty: UkPropertyBody,
)

@Serializable
data class UkPropertyBody(
    val income:   UkPropertyIncomeBody?   = null,
    val expenses: UkPropertyExpensesBody? = null,
)

@Serializable
data class UkPropertyIncomeBody(
    val periodAmount:         Double? = null,
    val taxDeducted:          Double? = null,
    val premiumsOfLeaseGrant: Double? = null,
    val reversePremiums:      Double? = null,
    val otherIncome:          Double? = null,
)

@Serializable
data class UkPropertyExpensesBody(
    val premisesRunningCosts:                    Double? = null,
    val repairsAndMaintenance:                   Double? = null,
    val financialCosts:                          Double? = null,
    val professionalFees:                        Double? = null,
    val costOfServices:                          Double? = null,
    val other:                                   Double? = null,
    val residentialFinancialCost:                Double? = null,
    val travelCosts:                             Double? = null,
    val residentialFinancialCostsCarriedForward: Double? = null,
)

// ── Response model ────────────────────────────────────────────────────────────

data class SubmissionResult(
    val success:    Boolean,
    val statusCode: Int,
    val message:    String,
)

// ── Client ────────────────────────────────────────────────────────────────────

class PropertySubmissionClient(private val apiClient: HmrcApiClient) {

    /**
     * Submits a cumulative UK property period summary for 2025-26 onwards.
     * Uses PUT — idempotent, replaces any previous submission for the tax year.
     *
     * [fromDate] is always the tax year start, e.g. "2025-04-06".
     * [toDate]   is the end of the latest quarter being reported, e.g. "2025-07-05".
     *
     * When [income] and [expenses] are both null the body still contains valid
     * dates and an empty ukProperty object, which is sufficient for HMRC to
     * mark the obligation as Fulfilled.
     *
     * @param nino       User's National Insurance number
     * @param businessId HMRC business ID for the UK property business
     * @param taxYear    Format "2025-26"
     * @param fromDate   Tax year start date, format "YYYY-MM-DD"
     * @param toDate     Quarter end date, format "YYYY-MM-DD"
     * @param income     Year-to-date income figures, or null if none
     * @param expenses   Year-to-date expense figures, or null if none
     */
    suspend fun submitCumulative(
        nino:       String,
        businessId: String,
        taxYear:    String,
        fromDate:   String,
        toDate:     String,
        income:     UkPropertyIncomeBody?,
        expenses:   UkPropertyExpensesBody?,
        context:    ClientContext,
    ): SubmissionResult = withContext(Dispatchers.IO) {
        val path = "/individuals/business/property/uk/$nino/$businessId/cumulative/$taxYear"

        val requestBody = UkPropertyCumulativeRequest(
            fromDate   = fromDate,
            toDate     = toDate,
            ukProperty = UkPropertyBody(
                income   = income,
                expenses = expenses,
            ),
        )

        val bodyJson = json.encodeToString(requestBody)
        logger.info { "Submitting cumulative property update to $path" }
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
            204  -> SubmissionResult(true,  204, "Submission accepted by HMRC ✓")
            400  -> SubmissionResult(false, 400, "Invalid request: ${response.body()}")
            401  -> SubmissionResult(false, 401, "Unauthorised — reconnect to HMRC")
            403  -> SubmissionResult(false, 403, "Forbidden — check your credentials")
            404  -> SubmissionResult(false, 404, "Not found — check the log for details")
            422  -> SubmissionResult(false, 422, "Unprocessable: ${response.body()}")
            else -> SubmissionResult(false, response.statusCode(), "Unexpected response: ${response.body()}")
        }
    }
}