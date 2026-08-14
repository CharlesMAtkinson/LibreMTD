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

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.charlesatkinson.libremtd.utils.ApiResult

private val logger = KotlinLogging.logger {}
private val json   = Json { ignoreUnknownKeys = true }

@Serializable
data class CreateForeignPropertyRequest(
    val propertyName: String,
    val countryCode: String,
)

@Serializable
data class CreateForeignPropertyResponse(
    val propertyId: String,
)

@Serializable
data class UpdateForeignPropertyRequest(
    val propertyName: String,
    val endDate: String? = null,
    val endReason: String? = null,
)

@Serializable
data class HmrcErrorBody(
    val code: String? = null,
    val message: String? = null,
)

/**
 * Valid values for the Update Foreign Property Details `endReason` field.
 * Source: https://developer.service.hmrc.gov.uk/api-documentation/docs/api/service/property-business-api/6.0/oas/page#tag/Foreign-Property-Details/paths/~1individuals~1business~1property~1foreign~1%7Bnino%7D~1details~1%7BpropId%7D~1%7BtaxYear%7D/put
 */
enum class ForeignPropertyEndReason(val apiValue: String, val displayName: String) {
    NO_LONGER_RENTING("no-longer-renting-property-out", "No longer letting this property"),
    DISPOSAL("disposal", "Property disposed of (sold or transferred)"),
    ADDED_IN_ERROR("added-in-error", "Property was added in error"),
}

class ForeignPropertyClient(private val apiClient: HmrcApiClient) {

    /**
     * Calls Create Foreign Property Details, which both validates the country
     * code against HMRC's own reference data and creates a live foreign-property
     * record scoped to the given business and tax year.
     *
     * See: https://developer.service.hmrc.gov.uk/api-documentation/docs/api/service/property-business-api/6.0
     */
    suspend fun create(
        nino: String,
        businessId: String,
        taxYear: String,
        propertyName: String,
        countryCode: String,
        context: ClientContext,
        testScenario: String? = null,
    ): ApiResult<String> {
        val extraHeaders = if (testScenario != null)
            mapOf("Gov-Test-Scenario" to testScenario)
        else
            emptyMap()

        val body = json.encodeToString(
            CreateForeignPropertyRequest.serializer(),
            CreateForeignPropertyRequest(propertyName = propertyName, countryCode = countryCode),
        )

        val response = apiClient.post(
            path         = "/individuals/business/property/foreign/$nino/$businessId/details/$taxYear",
            body         = body,
            context      = context,
            version      = "6.0",
            extraHeaders = extraHeaders,
        )

        if (response == null) {
            val msg = "Network error — could not reach HMRC. Check your internet connection."
            logger.error { msg }
            return ApiResult.Failure(msg)
        }

        if (response.statusCode() !in listOf(200, 201, 202, 204)) {
            val msg = try {
                val err = json.decodeFromString<HmrcErrorBody>(response.body())
                when (err.code) {
                    "FORMAT_COUNTRY_CODE" ->
                        "HMRC rejected that country code. Please enter a valid three-letter " +
                                "ISO 3166-1 Alpha-3 code (e.g. FRA for France, USA for United States)."
                    else -> err.message ?: "HMRC returned HTTP ${response.statusCode()}."
                }
            } catch (e: Exception) {
                "HMRC returned HTTP ${response.statusCode()}: ${response.body().trim()}"
            }
            logger.error { "Create foreign property failed: ${response.statusCode()} — ${response.body()}" }
            return ApiResult.Failure(msg)
        }

        return try {
            val parsed = json.decodeFromString<CreateForeignPropertyResponse>(response.body())
            logger.info { "Foreign property created, propertyId=${parsed.propertyId}" }
            ApiResult.Success(parsed.propertyId)
        } catch (e: Exception) {
            val msg = "Property may have been created but the response could not be parsed: ${e.message}"
            logger.error(e) { msg }
            ApiResult.Failure(msg, e)
        }
    }

    /**
     * Calls Update Foreign Property Details with an endDate/endReason, which
     * is how a foreign property is "ended" on HMRC's side — there is no
     * separate delete endpoint. [taxYear] must be the tax year the property
     * was originally registered under (see [ForeignPropertyClient.create]),
     * not necessarily the tax year [endDate] falls in.
     *
     * Note this endpoint takes propertyId in the path, not businessId.
     */
    suspend fun end(
        nino: String,
        propertyId: String,
        taxYear: String,
        propertyName: String,
        endDate: String,
        endReason: ForeignPropertyEndReason,
        context: ClientContext,
        testScenario: String? = null,
    ): ApiResult<Unit> {
        val extraHeaders = if (testScenario != null)
            mapOf("Gov-Test-Scenario" to testScenario)
        else
            emptyMap()

        val body = json.encodeToString(
            UpdateForeignPropertyRequest.serializer(),
            UpdateForeignPropertyRequest(
                propertyName = propertyName,
                endDate      = endDate,
                endReason    = endReason.apiValue,
            ),
        )

        val response = apiClient.put(
            path         = "/individuals/business/property/foreign/$nino/details/$propertyId/$taxYear",
            body         = body,
            context      = context,
            version      = "6.0",
            extraHeaders = extraHeaders,
        )

        if (response == null) {
            val msg = "Network error — could not reach HMRC. Check your internet connection."
            logger.error { msg }
            return ApiResult.Failure(msg)
        }

        if (response.statusCode() !in listOf(200, 201, 202, 204)) {
            val msg = try {
                val err = json.decodeFromString<HmrcErrorBody>(response.body())
                err.message ?: "HMRC returned HTTP ${response.statusCode()}."
            } catch (e: Exception) {
                "HMRC returned HTTP ${response.statusCode()}: ${response.body().trim()}"
            }
            logger.error { "End foreign property failed: ${response.statusCode()} — ${response.body()}" }
            return ApiResult.Failure(msg)
        }

        logger.info { "Foreign property ended: propertyId=$propertyId, taxYear=$taxYear, reason=${endReason.apiValue}" }
        return ApiResult.Success(Unit)
    }
}