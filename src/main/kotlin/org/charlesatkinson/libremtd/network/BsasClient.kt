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
import kotlinx.serialization.json.*
import mu.KotlinLogging
import org.charlesatkinson.libremtd.api.models.*
import org.charlesatkinson.libremtd.database.taxYearDateRange
import org.charlesatkinson.libremtd.utils.ApiResult

private val logger = KotlinLogging.logger {}

/**
 * How a BSAS retrieval ended. Three outcomes rather than two, because
 * "HMRC has nothing for us" (404) is harmless and the flow can carry on,
 * whereas "we could not read what HMRC sent" is not: the final declaration
 * cannot be undone, so the flow must stop.
 */
sealed class BsasRetrieval<out T> {
    data class Found<T>(val data: T) : BsasRetrieval<T>()

    /** HTTP 404: there is no adjustable summary, so there is nothing to adjust. */
    object NotAvailable : BsasRetrieval<Nothing>()

    /** Anything else which went wrong, including a response which could not be parsed. */
    data class Failed(val message: String) : BsasRetrieval<Nothing>()
}

/**
 * Maps an HTTP status and body to a [BsasRetrieval]. A top-level function so
 * it can be unit tested without HTTP.
 *
 * [what] names the thing retrieved, for messages, e.g. "foreign property BSAS".
 */
internal fun <T> interpretBsasResponse(
    code:  Int,
    body:  String,
    what:  String,
    parse: (String) -> T,
): BsasRetrieval<T> = when (code) {
    200  -> try {
        BsasRetrieval.Found(parse(body))
    } catch (e: Exception) {
        BsasRetrieval.Failed("HMRC's $what response could not be read: ${e.message}")
    }
    404  -> BsasRetrieval.NotAvailable
    403  -> BsasRetrieval.Failed("Not authorised to retrieve the $what.")
    400  -> BsasRetrieval.Failed("Bad request (NINO or parameter error) retrieving the $what: $body")
    422  -> BsasRetrieval.Failed("HMRC cannot fulfil the request for the $what: $body")
    else -> BsasRetrieval.Failed("Unexpected HTTP $code retrieving the $what: $body")
}

// ── Error codes ───────────────────────────────────────────────────────────────

/**
 * Plain wording for the HMRC error codes a BSAS call can return, checked
 * against HMRC's BSAS 7.0 specification. HMRC returns nearly all of these
 * with HTTP 400 (only RULE_REQUEST_CANNOT_BE_FULFILLED is 422), so codes are
 * looked up in the body whatever the status. A code not listed here falls
 * back to the caller's generic message, which carries HMRC's own body.
 */
private val BSAS_ERROR_MESSAGES = mapOf(
    "RULE_OBLIGATIONS_NOT_MET" to
            "HMRC says the quarterly obligations for this tax year have not all been met. " +
            "Fulfil them first.",
    "RULE_ACCOUNTING_PERIOD_NOT_ENDED" to
            "The tax year has not ended yet, so HMRC will not provide an adjustable summary. " +
            "Try again after 5 April.",
    "RULE_NO_ACCOUNTING_PERIOD" to
            "HMRC has no accounting period for this business and tax year.",
    "RULE_ACCOUNTING_PERIOD_NOT_ALIGNED" to
            "HMRC says the accounting period does not line up with the tax year (6 April to 5 April).",
    "RULE_ACCOUNTING_PERIOD_NOT_SUPPORTED" to
            "HMRC does not support an accounting period as early as this one.",
    "RULE_TAX_YEAR_NOT_SUPPORTED" to
            "HMRC does not support this tax year for adjustable summaries.",
    "RULE_OUTSIDE_AMENDMENT_WINDOW" to
            "The window for amending this tax year has closed.",
    "RULE_ALREADY_ADJUSTED" to
            "HMRC says a summary may only be adjusted once. A new summary is needed: " +
            "start the final declaration again.",
    "RULE_SUMMARY_STATUS_SUPERSEDED" to
            "HMRC says a newer summary exists for this accounting period. " +
            "Start the final declaration again to get the latest one.",
    "RULE_SUMMARY_STATUS_INVALID" to
            "HMRC says the periodic data has changed since this summary was made. " +
            "Start the final declaration again to get a new summary.",
    "RULE_TYPE_OF_BUSINESS_INCORRECT" to
            "HMRC says this summary is for a different type of business (UK or foreign property) " +
            "from the one being adjusted. Start the final declaration again.",
    "RULE_RESULTING_VALUE_NOT_PERMITTED" to
            "HMRC says one or more of these adjustments would make a figure negative, which is " +
            "not permitted. Adjustments are changes, not actual amounts: check the figures you " +
            "entered and reduce the adjustment.",
    "RULE_ZERO_ADJUSTMENTS_INVALID" to
            "HMRC says zero adjustments can only be set to true.",
    "RULE_BOTH_ADJUSTMENTS_SUPPLIED" to
            "HMRC says adjustments and zero adjustments were both supplied. Only one is allowed.",
    "RULE_PROPERTY_INCOME_ALLOWANCE_CLAIMED" to
            "HMRC says the property income allowance has been claimed for this business, so " +
            "expenses cannot be adjusted.",
    "RULE_OVER_CONSOLIDATED_EXPENSES_THRESHOLD" to
            "HMRC says the adjustment is not allowed because turnover is over the threshold for " +
            "consolidated expenses.",
    "RULE_PROPERTY_ID_MISMATCH" to
            "HMRC says a property ID in the adjustments does not belong to this summary.",
    "RULE_DUPLICATE_PROPERTY_ID" to
            "HMRC says the same property ID appears more than once in the adjustments.",
    "RULE_REQUEST_CANNOT_BE_FULFILLED" to
            "HMRC cannot process this request at present, for reasons it does not give. " +
            "Try again later.",
    "RULE_INCORRECT_OR_EMPTY_BODY_SUBMITTED" to
            "HMRC says the request was empty or did not match what it expects. This is a fault " +
            "in LibreMTD: please report it, with the log.",
)

/**
 * Every error code in an HMRC error body: the top-level "code", and the
 * "code" of each entry in an "errors" array. Empty if the body is not JSON.
 */
internal fun hmrcErrorCodes(body: String): List<String> = try {
    val root = json.parseToJsonElement(body).jsonObject
    buildList {
        root["code"]?.jsonPrimitive?.contentOrNull?.let { add(it) }
        root["errors"]?.jsonArray?.forEach { entry ->
            entry.jsonObject["code"]?.jsonPrimitive?.contentOrNull?.let { add(it) }
        }
    }
} catch (e: Exception) {
    emptyList()
}

/**
 * The message for an HMRC error body: plain wording for each code which is
 * known, followed by the codes themselves for support queries. If no code is
 * known, [fallback] is returned unchanged.
 */
internal fun bsasErrorMessage(body: String, fallback: String): String {
    val codes = hmrcErrorCodes(body)
    val known = codes.mapNotNull { BSAS_ERROR_MESSAGES[it] }
    if (known.isEmpty()) return fallback
    return known.joinToString("\n\n") + "\n\n(HMRC code: ${codes.joinToString()})"
}

// ── Trigger ───────────────────────────────────────────────────────────────────

internal const val BSAS_TYPE_UK      = "uk-property"
internal const val BSAS_TYPE_FOREIGN = "foreign-property"

/** HMRC's pattern for a business ID, as given in the BSAS 7.0 trigger request. */
internal val BUSINESS_ID_REGEX = Regex("^X[A-Z0-9]IS[0-9]{11}$")

@Serializable
internal data class TriggerBsasResponse(val calculationId: String)

/**
 * The JSON body for Trigger a Business Source Adjustable Summary:
 *
 *   { "accountingPeriod": { "startDate": "2026-04-06", "endDate": "2027-04-05" },
 *     "typeOfBusiness": "uk-property", "businessId": "X..." }
 *
 * All three fields are required by HMRC. The accounting period is the whole
 * tax year, 6 April to 5 April.
 */
internal fun buildTriggerBsasJson(typeOfBusiness: String, businessId: String, taxYear: String): String {
    require(typeOfBusiness == BSAS_TYPE_UK || typeOfBusiness == BSAS_TYPE_FOREIGN) {
        "typeOfBusiness must be \"$BSAS_TYPE_UK\" or \"$BSAS_TYPE_FOREIGN\", not \"$typeOfBusiness\""
    }
    val (startDate, endDate) = taxYearDateRange(taxYear)
    return buildJsonObject {
        putJsonObject("accountingPeriod") {
            put("startDate", startDate)
            put("endDate", endDate)
        }
        put("typeOfBusiness", typeOfBusiness)
        put("businessId", businessId)
    }.toString()
}

/**
 * Maps the HTTP status and body of a trigger request to the new
 * calculationId, or a failure with a readable message. A top-level function
 * so it can be unit tested without HTTP.
 */
internal fun interpretTriggerBsasResponse(code: Int, body: String, what: String): ApiResult<String> = when (code) {
    200  -> try {
        ApiResult.Success(json.decodeFromString<TriggerBsasResponse>(body).calculationId)
    } catch (e: Exception) {
        ApiResult.Failure("HMRC's response to the $what request could not be read: ${e.message}", e)
    }
    404  -> ApiResult.Failure(
        bsasErrorMessage(body, "HMRC could not find the business or accounting period for the $what request.")
    )
    403  -> ApiResult.Failure("Not authorised to request the $what.")
    400  -> ApiResult.Failure(bsasErrorMessage(body, "Validation error requesting the $what: $body"))
    422  -> ApiResult.Failure(bsasErrorMessage(body, "HMRC cannot fulfil the request for the $what: $body"))
    else -> ApiResult.Failure("Unexpected HTTP $code requesting the $what: $body")
}

/**
 * Maps the HTTP status and body of an adjustments submission to a result.
 * Shared by the UK and foreign submissions. [what] names the summary, for
 * messages, e.g. "UK property BSAS".
 */
internal fun interpretBsasSubmitResponse(code: Int, body: String, what: String): ApiResult<Unit> = when (code) {
    200, 204 -> ApiResult.Success(Unit)
    404      -> ApiResult.Failure(bsasErrorMessage(body, "$what not found – calculationId may be stale."))
    403      -> ApiResult.Failure("Not authorised to submit these adjustments.")
    400      -> ApiResult.Failure(bsasErrorMessage(body, "Validation error: $body"))
    422      -> ApiResult.Failure(bsasErrorMessage(body, "HMRC cannot fulfil this request: $body"))
    else     -> ApiResult.Failure("Unexpected HTTP $code: $body")
}

// ── POST bodies ───────────────────────────────────────────────────────────────

/**
 * The JSON body for Submit Foreign Property Accounting Adjustments for tax
 * years 2026-27 onwards:
 *
 *   { "foreignProperty": { "propertyLevelDetail": [
 *       { "propertyId": "...", "income": {...}, "expenses": {...} } ] } }
 *
 * A null field is left out, as is an income or expenses object which is null.
 */
internal fun buildForeignPostJson(payload: ForeignPropertyBsasPostPayload): String {
    val propertiesArray = buildJsonArray {
        payload.propertyLevelDetail.forEach { property ->
            addJsonObject {
                put("propertyId", property.propertyId)
                property.income?.let { inc ->
                    putJsonObject("income") {
                        inc.totalRentsReceived?.let   { put("totalRentsReceived",   it) }
                        inc.premiumsOfLeaseGrant?.let { put("premiumsOfLeaseGrant", it) }
                        inc.otherPropertyIncome?.let  { put("otherPropertyIncome",  it) }
                    }
                }
                property.expenses?.let { exp ->
                    putJsonObject("expenses") {
                        exp.premisesRunningCosts?.let  { put("premisesRunningCosts",  it) }
                        exp.repairsAndMaintenance?.let { put("repairsAndMaintenance", it) }
                        exp.financialCosts?.let        { put("financialCosts",        it) }
                        exp.professionalFees?.let      { put("professionalFees",      it) }
                        exp.costOfServices?.let        { put("costOfServices",        it) }
                        exp.travelCosts?.let           { put("travelCosts",           it) }
                        exp.other?.let                 { put("other",                 it) }
                    }
                }
            }
        }
    }

    return buildJsonObject {
        putJsonObject("foreignProperty") {
            put("propertyLevelDetail", propertiesArray)
        }
    }.toString()
}

/**
 * The JSON body for Submit UK Property Accounting Adjustments.
 * residentialFinancialCost is never sent: HMRC rejects it for 2026-27
 * onwards.
 */
internal fun buildUkPostJson(payload: BsasPostPayload): String {
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
                    exp.premisesRunningCosts?.let  { put("premisesRunningCosts",  it) }
                    exp.repairsAndMaintenance?.let { put("repairsAndMaintenance", it) }
                    exp.financialCosts?.let        { put("financialCosts",        it) }
                    exp.professionalFees?.let      { put("professionalFees",      it) }
                    exp.costOfServices?.let        { put("costOfServices",        it) }
                    exp.other?.let                 { put("other",                 it) }
                    exp.travelCosts?.let           { put("travelCosts",           it) }
                }
            }
        }
    }
    return buildJsonObject { put("ukProperty", ukProperty) }.toString()
}

class BsasClient(private val client: HmrcApiClient) {

    companion object {
        private const val API_VERSION = "7.0"
    }

    /**
     * Triggers a new Business Source Adjustable Summary for the whole of
     * [taxYear] and returns its calculationId. This is the only valid source
     * of the calculationId used by the retrieve and submit calls: it must not
     * be the id of an intent-to-finalise calculation.
     *
     * A BSAS can be adjusted once only, so every adjustment needs a freshly
     * triggered summary. [typeOfBusiness] is [BSAS_TYPE_UK] or
     * [BSAS_TYPE_FOREIGN].
     */
    suspend fun triggerBsas(
        nino:           String,
        businessId:     String,
        typeOfBusiness: String,
        taxYear:        String,
        context:        ClientContext,
        testScenario:   String? = null,
    ): ApiResult<String> {
        val what = if (typeOfBusiness == BSAS_TYPE_FOREIGN) "foreign property BSAS" else "UK property BSAS"

        if (!BUSINESS_ID_REGEX.matches(businessId)) {
            return ApiResult.Failure(
                "The $typeOfBusiness business ID \"$businessId\" is not in the form HMRC requires " +
                        "(for example XAIS12345678901). Fetch it again in Settings."
            )
        }

        val path = "/individuals/self-assessment/adjustable-summary/$nino/trigger"
        val body = buildTriggerBsasJson(typeOfBusiness, businessId, taxYear)
        val extraHeaders = testScenario?.let { mapOf("Gov-Test-Scenario" to it) } ?: emptyMap()

        logger.info { "$what trigger POST: $path  payload=$body" }

        val response = client.post(
            path         = path,
            body         = body,
            context      = context,
            version      = API_VERSION,
            extraHeaders = extraHeaders,
        ) ?: return ApiResult.Failure("No response from HMRC. Check your network connection.")

        val code = response.statusCode()
        logger.info { "$what trigger response: HTTP $code" }

        val result = interpretTriggerBsasResponse(code, response.body().trim(), what)
        if (result is ApiResult.Failure) logger.warn { "$what trigger failed: ${result.message}" }
        return result
    }

    suspend fun getUkPropertyBsas(
        nino: String,
        calculationId: String,
        taxYear: String,
        context: ClientContext,
        testScenario: String? = null,
    ): BsasRetrieval<UkPropertyBsasResponse> {
        val path = "/individuals/self-assessment/adjustable-summary" +
                "/$nino/uk-property/$calculationId/$taxYear"
        return retrieve(path, "UK property BSAS", context, testScenario) {
            json.decodeFromString<UkPropertyBsasResponse>(it)
        }
    }

    suspend fun getForeignPropertyBsas(
        nino:          String,
        calculationId: String,
        taxYear:       String,
        context:       ClientContext,
        testScenario:  String? = null,
    ): BsasRetrieval<ForeignPropertyBsasResponse> {
        val path = "/individuals/self-assessment/adjustable-summary" +
                "/$nino/foreign-property/$calculationId/$taxYear"
        return retrieve(path, "foreign property BSAS", context, testScenario) {
            json.decodeFromString<ForeignPropertyBsasResponse>(it)
        }
    }

    private suspend fun <T> retrieve(
        path:         String,
        what:         String,
        context:      ClientContext,
        testScenario: String?,
        parse:        (String) -> T,
    ): BsasRetrieval<T> {
        val extraHeaders = testScenario?.let { mapOf("Gov-Test-Scenario" to it) } ?: emptyMap()

        logger.info { "$what GET: $path" }

        val response = client.get(
            path         = path,
            context      = context,
            version      = API_VERSION,
            extraHeaders = extraHeaders,
        ) ?: return BsasRetrieval.Failed("No response from HMRC. Check your network connection.")

        val code = response.statusCode()
        logger.info { "$what GET response: HTTP $code" }

        val result = interpretBsasResponse(code, response.body().trim(), what, parse)
        if (result is BsasRetrieval.Failed) logger.warn { "$what GET failed: ${result.message}" }
        return result
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

        val body = buildUkPostJson(payload)
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

        return interpretBsasSubmitResponse(code, responseBody, "BSAS")
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

        return interpretBsasSubmitResponse(code, responseBody, "Foreign BSAS")
    }
}
