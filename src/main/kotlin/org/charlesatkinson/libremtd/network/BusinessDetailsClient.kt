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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.charlesatkinson.libremtd.utils.ApiResult

private val logger = KotlinLogging.logger {}
private val json   = Json { ignoreUnknownKeys = true }

// ── API response model ────────────────────────────────────────────────────────

@Serializable
data class BusinessDetailsResponse(
    val listOfBusinesses: List<BusinessDetails>,
)

@Serializable
data class BusinessDetails(
    val typeOfBusiness: String,
    val businessId:     String,
    val tradingName:    String? = null,
    val businessAddressLineOne: String? = null,
    val businessAddressPostCode: String? = null,
)

// ── Client ────────────────────────────────────────────────────────────────────

class BusinessDetailsClient(private val apiClient: HmrcApiClient) {

    /**
     * Retrieves the list of businesses for a user and returns the businessId
     * of their UK property business.
     *
     * Returns [ApiResult.Success] with the businessId string, or
     * [ApiResult.Failure] with a user-readable description of what went wrong.
     *
     * @param nino    The user's National Insurance number
     * @param context Current window dimensions for fraud prevention headers.
     *                See: https://developer.service.hmrc.gov.uk/guides/fraud-prevention/
     *
     * See: https://developer.service.hmrc.gov.uk/api-documentation/docs/api/service/business-details-api/2.0
     */
    suspend fun fetchUkPropertyBusinessId(
        nino:    String,
        context: ClientContext,
        testScenario: String? = null,
    ): ApiResult<String> {
        val extraHeaders = if (testScenario != null)
            mapOf("Gov-Test-Scenario" to testScenario)
        else
            emptyMap()

        val response = apiClient.get(
            path         = "/individuals/business/details/$nino/list",
            context      = context,
            version      = "2.0",
            extraHeaders = extraHeaders,
        )

        if (response == null) {
            val msg = "Network error — could not reach HMRC. Check your internet connection."
            logger.error { msg }
            return ApiResult.Failure(msg)
        }

        if (response.statusCode() != 200) {
            val msg = buildString {
                append("HMRC returned HTTP ${response.statusCode()} fetching business details")
                val body = response.body().trim()
                if (body.isNotEmpty()) append(":\n$body")
            }
            logger.error { "Business details fetch failed: ${response.statusCode()} — ${response.body()}" }
            return ApiResult.Failure(msg)
        }

        return try {
            val parsed = json.decodeFromString<BusinessDetailsResponse>(response.body())
            val businessId = parsed.listOfBusinesses
                .firstOrNull { it.typeOfBusiness == "uk-property" }
                ?.businessId

            if (businessId != null) {
                logger.info { "Found UK property businessId: $businessId" }
                ApiResult.Success(businessId)
            } else {
                val msg = "No UK property business found for NINO $nino. " +
                        "Check that you have a UK property business registered with HMRC."
                logger.error { msg }
                ApiResult.Failure(msg)
            }
        } catch (e: Exception) {
            val msg = "Failed to parse business details response: ${e.message}"
            logger.error(e) { msg }
            ApiResult.Failure(msg, e)
        }
    }

    suspend fun fetchForeignPropertyBusinessId(
        nino:    String,
        context: ClientContext,
        testScenario: String? = null,
    ): ApiResult<String> {
        val extraHeaders = if (testScenario != null)
            mapOf("Gov-Test-Scenario" to testScenario)
        else
            emptyMap()

        val response = apiClient.get(
            path         = "/individuals/business/details/$nino/list",
            context      = context,
            version      = "2.0",
            extraHeaders = extraHeaders,
        )

        if (response == null) {
            val msg = "Network error — could not reach HMRC. Check your internet connection."
            logger.error { msg }
            return ApiResult.Failure(msg)
        }

        if (response.statusCode() != 200) {
            val msg = buildString {
                append("HMRC returned HTTP ${response.statusCode()} fetching business details")
                val body = response.body().trim()
                if (body.isNotEmpty()) append(":\n$body")
            }
            logger.error { "Business details fetch failed: ${response.statusCode()} — ${response.body()}" }
            return ApiResult.Failure(msg)
        }

        return try {
            val parsed = json.decodeFromString<BusinessDetailsResponse>(response.body())
            val businessId = parsed.listOfBusinesses
                .firstOrNull { it.typeOfBusiness == "foreign-property" }
                ?.businessId

            if (businessId != null) {
                logger.info { "Found foreign property businessId: $businessId" }
                ApiResult.Success(businessId)
            } else {
                val msg = "No foreign property business found for NINO $nino. " +
                        "Check that you have a foreign property business registered with HMRC."
                logger.error { msg }
                ApiResult.Failure(msg)
            }
        } catch (e: Exception) {
            val msg = "Failed to parse business details response: ${e.message}"
            logger.error(e) { msg }
            ApiResult.Failure(msg, e)
        }
    }
}
