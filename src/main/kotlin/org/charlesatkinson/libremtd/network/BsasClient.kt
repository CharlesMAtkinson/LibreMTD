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

import kotlinx.serialization.json.*
import mu.KotlinLogging
import org.charlesatkinson.libremtd.api.models.*
import org.charlesatkinson.libremtd.utils.ApiResult

private val logger = KotlinLogging.logger {}

private val json = Json { ignoreUnknownKeys = true }

class BsasClient(private val client: HmrcApiClient) {

    companion object {
        private const val API_VERSION = "7.0"
    }

    suspend fun getUkPropertyBsas(
        nino: String,
        calculationId: String,
        taxYear: String,
        context: ClientContext,
        testScenario: String? = null,
    ): ApiResult<UkPropertyBsasResponse> {
        val path = "/individuals/self-assessment/adjustable-summary" +
                "/$nino/uk-property/$calculationId/$taxYear"

        val extraHeaders = testScenario?.let { mapOf("Gov-Test-Scenario" to it) } ?: emptyMap()

        logger.info { "BSAS GET: $path" }

        val response = client.get(
            path         = path,
            context      = context,
            version      = API_VERSION,
            extraHeaders = extraHeaders,
        ) ?: return ApiResult.Failure("No response from HMRC")

        val code = response.statusCode()
        val body = response.body().trim()

        logger.info { "BSAS GET response: HTTP $code" }

        return when (code) {
            200  -> runCatching { json.decodeFromString<UkPropertyBsasResponse>(body) }
                .fold(
                    onSuccess = { ApiResult.Success(it) },
                    onFailure = { ApiResult.Failure("Failed to parse BSAS response: ${it.message}") },
                )
            404  -> ApiResult.Failure("BSAS not found — no summary available for this calculation.")
            403  -> ApiResult.Failure("Not authorised to retrieve this BSAS.")
            400  -> ApiResult.Failure("Bad request (NINO or parameter error): $body")
            422  -> ApiResult.Failure("HMRC cannot fulfil this request: $body")
            else -> ApiResult.Failure("Unexpected HTTP $code: $body")
        }
    }

    suspend fun submitUkPropertyBsas(
        nino: String,
        calculationId: String,
        taxYear: String,
        payload: BsasPostPayload,
        context: ClientContext,
        testScenario: String? = null,
    ): ApiResult<Unit> {
        val path = "/individuals/self-assessment/adjustable-summary" +
                "/$nino/uk-property/$calculationId/adjust/$taxYear"

        val body = buildPostJson(payload)
        val extraHeaders = testScenario?.let { mapOf("Gov-Test-Scenario" to it) } ?: emptyMap()

        logger.info { "BSAS POST: $path  payload=$body" }

        val response = client.post(
            path         = path,
            body         = body,
            context      = context,
            version      = API_VERSION,
            extraHeaders = extraHeaders,
        ) ?: return ApiResult.Failure("No response from HMRC")

        val code = response.statusCode()
        val responseBody = response.body().trim()

        logger.info { "BSAS POST response: HTTP $code" }

        return when (code) {
            200, 204 -> ApiResult.Success(Unit)
            404      -> ApiResult.Failure("BSAS not found — calculationId may be stale.")
            403      -> ApiResult.Failure("Not authorised to submit these adjustments.")
            400      -> ApiResult.Failure("Validation error: $responseBody")
            422      -> ApiResult.Failure("HMRC cannot fulfil this request: $responseBody")
            else     -> ApiResult.Failure("Unexpected HTTP $code: $responseBody")
        }
    }

    suspend fun getForeignPropertyBsas(
        nino:          String,
        calculationId: String,
        taxYear:       String,
        context:       ClientContext,
        testScenario:  String? = null,
    ): ApiResult<ForeignPropertyBsasResponse> {
        val path = "/individuals/self-assessment/adjustable-summary" +
                "/$nino/foreign-property/$calculationId/$taxYear"

        val extraHeaders = testScenario?.let { mapOf("Gov-Test-Scenario" to it) } ?: emptyMap()

        logger.info { "Foreign BSAS GET: $path" }

        val response = client.get(
            path         = path,
            context      = context,
            version      = API_VERSION,
            extraHeaders = extraHeaders,
        ) ?: return ApiResult.Failure("No response from HMRC")

        val code = response.statusCode()
        val body = response.body().trim()

        logger.info { "Foreign BSAS GET response: HTTP $code" }

        return when (code) {
            200  -> runCatching { json.decodeFromString<ForeignPropertyBsasResponse>(body) }
                .fold(
                    onSuccess = { ApiResult.Success(it) },
                    onFailure = { ApiResult.Failure("Failed to parse foreign BSAS response: ${it.message}") },
                )
            404  -> ApiResult.Failure("Foreign BSAS not found — no summary available for this calculation.")
            403  -> ApiResult.Failure("Not authorised to retrieve this foreign BSAS.")
            400  -> ApiResult.Failure("Bad request (NINO or parameter error): $body")
            422  -> ApiResult.Failure("HMRC cannot fulfil this request: $body")
            else -> ApiResult.Failure("Unexpected HTTP $code: $body")
        }
    }

    suspend fun submitForeignPropertyBsas(
        nino:          String,
        calculationId: String,
        taxYear:       String,
        payload:       ForeignPropertyBsasPostPayload,
        context:       ClientContext,
        testScenario:  String? = null,
    ): ApiResult<Unit> {
        val path = "/individuals/self-assessment/adjustable-summary" +
                "/$nino/foreign-property/$calculationId/adjust/$taxYear"

        val body = buildForeignPostJson(payload)
        val extraHeaders = testScenario?.let { mapOf("Gov-Test-Scenario" to it) } ?: emptyMap()

        logger.info { "Foreign BSAS POST: $path  payload=$body" }

        val response = client.post(
            path         = path,
            body         = body,
            context      = context,
            version      = API_VERSION,
            extraHeaders = extraHeaders,
        ) ?: return ApiResult.Failure("No response from HMRC")

        val code         = response.statusCode()
        val responseBody = response.body().trim()

        logger.info { "Foreign BSAS POST response: HTTP $code" }

        return when (code) {
            200, 204 -> ApiResult.Success(Unit)
            404      -> ApiResult.Failure("Foreign BSAS not found — calculationId may be stale.")
            403      -> ApiResult.Failure("Not authorised to submit these adjustments.")
            400      -> ApiResult.Failure("Validation error: $responseBody")
            422      -> ApiResult.Failure("HMRC cannot fulfil this request: $responseBody")
            else     -> ApiResult.Failure("Unexpected HTTP $code: $responseBody")
        }
    }

    private fun buildForeignPostJson(payload: ForeignPropertyBsasPostPayload): String {
        val countriesArray = buildJsonArray {
            payload.countryLevelDetail.forEach { country ->
                addJsonObject {
                    put("countryCode", country.countryCode)
                    country.income?.let { inc ->
                        putJsonObject("income") {
                            inc.totalRentsReceived?.let   { put("totalRentsReceived",   it) }
                            inc.premiumsOfLeaseGrant?.let { put("premiumsOfLeaseGrant", it) }
                            inc.otherPropertyIncome?.let  { put("otherPropertyIncome",  it) }
                        }
                    }
                    country.expenses?.let { exp ->
                        putJsonObject("expenses") {
                            exp.premisesRunningCosts?.let    { put("premisesRunningCosts",     it) }
                            exp.repairsAndMaintenance?.let   { put("repairsAndMaintenance",    it) }
                            exp.financialCosts?.let          { put("financialCosts",           it) }
                            exp.professionalFees?.let        { put("professionalFees",         it) }
                            exp.costOfServices?.let          { put("costOfServices",           it) }
                            exp.travelCosts?.let             { put("travelCosts",              it) }
                            exp.residentialFinancialCost?.let{ put("residentialFinancialCost", it) }
                            exp.other?.let                   { put("other",                    it) }
                        }
                    }
                }
            }
        }

        return buildJsonObject {
            putJsonObject("foreignProperty") {
                put("countryLevelDetail", countriesArray)
            }
        }.toString()
    }

    private fun buildPostJson(payload: BsasPostPayload): String {
        val ukProperty = when (payload) {
            is BsasPostPayload.Zero -> buildJsonObject {
                put("zeroAdjustments", true)
            }
            is BsasPostPayload.Adjustments -> buildJsonObject {
                payload.income?.let { inc ->
                    putJsonObject("income") {
                        inc.totalRentsReceived?.let   { put("totalRentsReceived",   it) }
                        inc.premiumsOfLeaseGrant?.let { put("premiumsOfLeaseGrant", it) }
                        inc.reversePremiums?.let      { put("reversePremiums",      it) }
                        inc.otherPropertyIncome?.let  { put("otherPropertyIncome",  it) }
                    }
                }
                payload.expenses?.let { exp ->
                    putJsonObject("expenses") {
                        exp.premisesRunningCosts?.let    { put("premisesRunningCosts",     it) }
                        exp.repairsAndMaintenance?.let   { put("repairsAndMaintenance",    it) }
                        exp.financialCosts?.let          { put("financialCosts",           it) }
                        exp.professionalFees?.let        { put("professionalFees",         it) }
                        exp.costOfServices?.let          { put("costOfServices",           it) }
                        exp.residentialFinancialCost?.let{ put("residentialFinancialCost", it) }
                        exp.other?.let                   { put("other",                    it) }
                        exp.travelCosts?.let             { put("travelCosts",              it) }
                    }
                }
            }
        }
        return buildJsonObject { put("ukProperty", ukProperty) }.toString()
    }
}