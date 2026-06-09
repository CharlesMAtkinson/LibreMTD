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

import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.charlesatkinson.libremtd.utils.ApiResult

private val logger = KotlinLogging.logger {}
private val json   = Json { ignoreUnknownKeys = true }

// ── Response models ───────────────────────────────────────────────────────────
//
// Maps the subset of fields returned by:
//   GET /individuals/calculations/{nino}/self-assessment/{taxYear}/{calculationId}
//
// Full schema: https://developer.service.hmrc.gov.uk/api-documentation/docs/api/
//              service/individual-calculations-api/8.0/oas/page
//
// Only the fields we display are mapped; ignoreUnknownKeys = true handles the rest.
//
// Notable v8 changes vs v5:
//   • Trigger URL gains a /trigger/{calculationType} suffix
//   • Trigger response is 202 Accepted (was 201 Created)
//   • class4Nics.totalClass4NicsCharged renamed to class4Nics.totalAmount
//   • metadata.calculationType is now "in-year" (hyphenated), not "inYear"

@Serializable
data class CalculationResponse(
    val metadata:    CalculationMetadata,
    val inputs:      CalculationInputs? = null,
    val calculation: CalculationDetail? = null,
)

@Serializable
data class CalculationMetadata(
    val calculationId:   String,
    val taxYear:         String,
    val calculationType: String? = null,   // "in-year" | "intent-to-finalise" | …
)

@Serializable
data class CalculationInputs(
    val personalInformation: PersonalInformation? = null,
)

@Serializable
data class PersonalInformation(
    val taxRegime: String? = null,         // "UK" | "Scotland" | "Wales"
)

@Serializable
data class CalculationDetail(
    val allowancesAndDeductions: AllowancesAndDeductions? = null,
    val taxCalculation:          TaxCalculation?          = null,
)

@Serializable
data class AllowancesAndDeductions(
    val personalAllowance: Double? = null,
)

@Serializable
data class TaxCalculation(
    val incomeTax:                 IncomeTax? = null,
    val nics:                      Nics?      = null,
    val totalIncomeTaxNicsCharged: Double?    = null,
)

@Serializable
data class IncomeTax(
    val totalIncomeReceivedFromAllSources: Double? = null,
    val totalAllowancesAndDeductions:      Double? = null,
    val totalTaxableIncome:                Double? = null,
    val incomeTaxCharged:                  Double? = null,
)

@Serializable
data class Nics(
    @SerialName("class4Nics") val class4Nics: Class4Nics? = null,
)

@Serializable
data class Class4Nics(
    // Field was named totalClass4NicsCharged in v5; renamed to totalAmount in v8.
    val totalAmount: Double? = null,
)

// ── Parsed summary ────────────────────────────────────────────────────────────

data class TaxCalculationSummary(
    val calculationId:    String,
    val taxYear:          String,
    val calculationType:  String,
    val totalIncome:      Double,
    val totalDeductions:  Double,
    val taxableIncome:    Double,
    val personalAllowance:Double,
    val incomeTax:        Double,
    val class4Nics:       Double,
    val totalLiability:   Double,
)

// ── Client ────────────────────────────────────────────────────────────────────

class IndividualCalculationsClient(private val apiClient: HmrcApiClient) {

    /**
     * Triggers a new tax calculation for [nino] / [taxYear] (e.g. "2025-26"),
     * waits briefly, then retrieves and parses the full result.
     *
     * Step 1 – POST /individuals/calculations/{nino}/self-assessment/{taxYear}
     *                /trigger/{calculationType}
     *   → 202 Accepted  { "calculationId": "…" }
     * Step 2 – (wait 5 s per HMRC guidance)
     * Step 3 – GET /individuals/calculations/{nino}/self-assessment/{taxYear}/{calculationId}
     *   → 200 OK  full calculation breakdown
     *
     * API version: 8.0
     * Docs: https://developer.service.hmrc.gov.uk/api-documentation/docs/api/
     *       service/individual-calculations-api/8.0
     *
     * [calculationType] values accepted for 2025-26: "in-year" | "intent-to-finalise"
     * Additional value for 2026-27 onwards:           "intent-to-amend"
     */
    suspend fun triggerAndFetch(
        nino: String,
        taxYear: String,           // e.g. "2025-26"
        context: ClientContext,
        testScenario: String? = null,
        calculationType: String = "in-year",
    ): ApiResult<TaxCalculationSummary> {

        val extraHeaders = if (testScenario != null)
            mapOf("Gov-Test-Scenario" to testScenario)
        else
            emptyMap()

        // ── Step 1: trigger calculation ───────────────────────────────────────
        //
        // v8 URL: POST …/{taxYear}/trigger/{calculationType}
        // v8 success response: 202 Accepted (v5 was 201 Created)

        val triggerPath =
            "/individuals/calculations/$nino/self-assessment/$taxYear/trigger/$calculationType"

        logger.info { "Triggering $calculationType calculation: POST $triggerPath" }

        val triggerResponse = apiClient.post(
            path = triggerPath,
            body = "{}",
            context = context,
            version = "8.0",
            extraHeaders = extraHeaders,
        )

        if (triggerResponse == null) {
            val msg = "Network error triggering calculation — check internet connection."
            logger.error { msg }
            return ApiResult.Failure(msg)
        }

        if (triggerResponse.statusCode() != 202) {
            val msg = buildString {
                append("HMRC returned HTTP ${triggerResponse.statusCode()} triggering calculation")
                val body = triggerResponse.body().trim()
                if (body.isNotEmpty()) append(":\n$body")
            }
            logger.error { "Trigger calculation failed: ${triggerResponse.statusCode()} — ${triggerResponse.body()}" }
            return ApiResult.Failure(msg)
        }

        val calculationId = try {
            // Body: { "calculationId": "f2fb30e5-4ab6-4a29-b3c1-c00000000001" }
            json.decodeFromString<CalculationIdResponse>(triggerResponse.body()).calculationId
        } catch (e: Exception) {
            val msg = "Failed to parse calculationId from trigger response: ${e.message}"
            logger.error(e) { msg }
            return ApiResult.Failure(msg, e)
        }

        logger.info { "Calculation triggered: id=$calculationId taxYear=$taxYear type=$calculationType" }

        // ── Step 2: wait for HMRC to compute the result ───────────────────────
        //
        // HMRC's service guide: "wait at least 5 seconds before attempting to
        // retrieve the calculation" to avoid a 404.

        logger.info { "Waiting 5 s before retrieving calculation $calculationId…" }
        delay(5_000L)

        // ── Step 3: retrieve calculation ──────────────────────────────────────

        val fetchPath =
            "/individuals/calculations/$nino/self-assessment/$taxYear/$calculationId"

        val fetchResponse = apiClient.get(
            path = fetchPath,
            context = context,
            version = "8.0",
            extraHeaders = extraHeaders,
        )

        if (fetchResponse == null) {
            val msg = "Network error retrieving calculation result."
            logger.error { msg }
            return ApiResult.Failure(msg)
        }

        if (fetchResponse.statusCode() != 200) {
            val msg = buildString {
                append("HMRC returned HTTP ${fetchResponse.statusCode()} retrieving calculation")
                val body = fetchResponse.body().trim()
                if (body.isNotEmpty()) append(":\n$body")
            }
            logger.error { "Fetch calculation failed: ${fetchResponse.statusCode()} — ${fetchResponse.body()}" }
            return ApiResult.Failure(msg)
        }

        logger.info { "Calculation response received for id=$calculationId" }

        return try {
            val parsed = json.decodeFromString<CalculationResponse>(fetchResponse.body())
            val calc = parsed.calculation
            val tax = calc?.taxCalculation

            val totalIncome = tax?.incomeTax?.totalIncomeReceivedFromAllSources ?: 0.0
            val totalDeductions = tax?.incomeTax?.totalAllowancesAndDeductions ?: 0.0
            val taxableIncome = tax?.incomeTax?.totalTaxableIncome ?: 0.0
            val personalAllowance = calc?.allowancesAndDeductions?.personalAllowance ?: 0.0
            val incomeTax = tax?.incomeTax?.incomeTaxCharged ?: 0.0
            // v8: renamed from totalClass4NicsCharged to totalAmount
            val class4Nics = tax?.nics?.class4Nics?.totalAmount ?: 0.0
            val totalLiability = tax?.totalIncomeTaxNicsCharged
                ?: (incomeTax + class4Nics)

            ApiResult.Success(
                TaxCalculationSummary(
                    calculationId = calculationId,
                    taxYear = parsed.metadata.taxYear,
                    calculationType = parsed.metadata.calculationType ?: calculationType,
                    totalIncome = totalIncome,
                    totalDeductions = totalDeductions,
                    taxableIncome = taxableIncome,
                    personalAllowance = personalAllowance,
                    incomeTax = incomeTax,
                    class4Nics = class4Nics,
                    totalLiability = totalLiability,
                )
            )
        } catch (e: Exception) {
            val msg = "Failed to parse calculation response: ${e.message}"
            logger.error(e) { msg }
            ApiResult.Failure(msg, e)
        }
    }

    /**
     * Submits the Self Assessment Final Declaration (crystallisation).
     *
     * Must be called after [triggerAndFetch] with calculationType = "intent-to-finalise".
     * Uses the calculationId returned by that call.
     *
     * POST /individuals/calculations/{nino}/self-assessment/{taxYear}/{calculationId}/crystallisation
     *
     * → 204 No Content on success (no response body)
     *
     * API version: 8.0Friday 5th June 10:30am
     */
    suspend fun submitFinalDeclaration(
        nino: String,
        taxYear: String,
        calculationId: String,
        context: ClientContext,
        testScenario: String? = null,
    ): ApiResult<Unit> {

        val extraHeaders = if (testScenario != null)
            mapOf("Gov-Test-Scenario" to testScenario)
        else
            emptyMap()

        val path = "/individuals/calculations/$nino/self-assessment/$taxYear/$calculationId/crystallisation"

        logger.info { "Submitting final declaration: POST $path" }

        val response = apiClient.post(
            path = path,
            body = "{}",
            context = context,
            version = "8.0",
            extraHeaders = extraHeaders,
        )

        if (response == null) {
            val msg = "Network error submitting final declaration — check internet connection."
            logger.error { msg }
            return ApiResult.Failure(msg)
        }

        return when (response.statusCode()) {
            204 -> {
                logger.info { "Final declaration accepted for taxYear=$taxYear calculationId=$calculationId" }
                ApiResult.Success(Unit)
            }

            else -> {
                val msg = buildString {
                    append("HMRC returned HTTP ${response.statusCode()} submitting final declaration")
                    val body = response.body().trim()
                    if (body.isNotEmpty()) append(":\n$body")
                }
                logger.error { msg }
                ApiResult.Failure(msg)
            }
        }
    }
}

// ── Internal model for trigger response ───────────────────────────────────────

@Serializable
private data class CalculationIdResponse(val calculationId: String)