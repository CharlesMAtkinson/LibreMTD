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

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; explicitNulls = false }

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

data class SubmissionResult(
    val success:    Boolean,
    val statusCode: Int,
    val message:    String,
)

class PropertyUkSubmissionClient(private val apiClient: HmrcApiClient) {

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

