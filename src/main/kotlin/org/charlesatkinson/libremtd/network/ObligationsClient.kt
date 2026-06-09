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

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.charlesatkinson.libremtd.ui.Obligation
import org.charlesatkinson.libremtd.ui.ObligationStatus
import org.charlesatkinson.libremtd.utils.ApiResult
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private val logger = KotlinLogging.logger {}
private val json   = Json { ignoreUnknownKeys = true }

// ── API response model ────────────────────────────────────────────────────────

@Serializable
data class ObligationsResponse(
    val obligations: List<ObligationGroup>,
)

@Serializable
data class ObligationGroup(
    val typeOfBusiness:    String,
    val businessId:        String? = null,
    val obligationDetails: List<ObligationDetail>,
)

@Serializable
data class ObligationDetail(
    val status:          String,
    val periodStartDate: String,
    val periodEndDate:   String,
    val dueDate:         String,
    val receivedDate:    String? = null,  // only present on fulfilled obligations
    val periodKey:       String? = null,
)

// ── Helpers ───────────────────────────────────────────────────────────────────

private val shortDateFmt = DateTimeFormatter.ofPattern("d MMM")

/**
 * Derives the HMRC period key ("#001" … "#004") from a period start date.
 *
 * Standard tax-year quarters (6-Apr start):
 *   #001  6 Apr – 5 Jul
 *   #002  6 Jul – 5 Oct
 *   #003  6 Oct – 5 Jan
 *   #004  6 Jan – 5 Apr
 */
private fun derivePeriodKey(startDate: LocalDate): String {
    val m = startDate.monthValue
    val d = startDate.dayOfMonth
    return when {
        (m == 4  && d >= 6) || m in 5..6  -> "#001"
        (m == 7  && d >= 6) || m in 8..9  -> "#002"
        (m == 10 && d >= 6) || m in 11..12 -> "#003"
        else                                -> "#004"   // Jan 1–5, Jan 6 – Mar, Apr 1–5
    }
}

/**
 * Derives the tax year string ("2025-26") that a period start date belongs to.
 * The tax year starts on 6 April.
 */
private fun deriveTaxYear(startDate: LocalDate): String {
    val m = startDate.monthValue
    val d = startDate.dayOfMonth
    val y = startDate.year
    val startYear = if (m > 4 || (m == 4 && d >= 6)) y else y - 1
    return "$startYear-${(startYear + 1).toString().takeLast(2)}"
}

/**
 * Produces a period label in the same format used by PeriodSelector.formatPeriod:
 *   "2025-26  #001  6 Apr to 5 Jul"
 *
 * Uses [periodKey] from the API when present, otherwise derives it from
 * [periodStartDate].  Falls back to the raw start-date string on parse failure.
 */
private fun formatObligationPeriod(
    periodStartDate: String,
    periodEndDate:   String,
    periodKey:       String?,
): String {
    return try {
        val start   = LocalDate.parse(periodStartDate)
        val end     = LocalDate.parse(periodEndDate)
        val taxYear = deriveTaxYear(start)
        val key     = periodKey ?: derivePeriodKey(start)
        "$taxYear  $key  ${start.format(shortDateFmt)} to ${end.format(shortDateFmt)}"
    } catch (_: DateTimeParseException) {
        periodKey ?: periodStartDate
    }
}

// ── Client ────────────────────────────────────────────────────────────────────

class ObligationsClient(private val apiClient: HmrcApiClient) {

    /**
     * Fetches income and expenditure obligations for a UK property business.
     *
     * Returns [ApiResult.Success] with the list of obligations, or
     * [ApiResult.Failure] with a user-readable message describing what went wrong.
     *
     * @param nino     The user's National Insurance number
     * @param fromDate ISO date string e.g. "2026-04-06"
     * @param toDate   ISO date string e.g. "2027-04-05"
     * @param context  Current window dimensions, included in the Gov-Client-Window-Size
     *                 fraud prevention header required on every HMRC API call.
     *                 See: https://developer.service.hmrc.gov.uk/guides/fraud-prevention/
     * @param status   "open", "fulfilled", or null for both
     */
    suspend fun fetchObligations(
        nino:         String,
        fromDate:     String,
        toDate:       String,
        context:      ClientContext,
        status:       String? = null,
        testScenario: String? = null,
    ): ApiResult<List<Obligation>> {
        val params = buildMap<String, String> {
            put("typeOfBusiness", "uk-property")
            put("fromDate",       fromDate)
            put("toDate",         toDate)
            status?.let { put("status", it) }
        }

        val extraHeaders = if (testScenario != null)
            mapOf("Gov-Test-Scenario" to testScenario)
        else
            emptyMap()

        val response = apiClient.get(
            path         = "/obligations/details/$nino/income-and-expenditure",
            params       = params,
            context      = context,
            version      = "3.0",
            extraHeaders = extraHeaders,
        )

        if (response == null) {
            val msg = "Network error — could not reach HMRC. Check your internet connection."
            logger.error { msg }
            return ApiResult.Failure(msg)
        }

        if (response.statusCode() != 200) {
            val msg = buildString {
                append("HMRC returned HTTP ${response.statusCode()}")
                val body = response.body().trim()
                if (body.isNotEmpty()) append(":\n$body")
            }
            logger.error { "Obligations fetch failed: ${response.statusCode()} — ${response.body()}" }
            return ApiResult.Failure(msg)
        }

        logger.info { "Obligations response: ${response.body()}" }

        return try {
            val parsed = json.decodeFromString<ObligationsResponse>(response.body())
            val obligations = parsed.obligations
                .flatMap { it.obligationDetails }
                .map { detail ->
                    Obligation(
                        periodKey = formatObligationPeriod(
                            periodStartDate = detail.periodStartDate,
                            periodEndDate   = detail.periodEndDate,
                            periodKey       = detail.periodKey,
                        ),
                        start  = detail.periodStartDate,
                        end    = detail.periodEndDate,
                        due    = detail.dueDate,
                        status = when (detail.status.lowercase()) {
                            "fulfilled" -> ObligationStatus.Fulfilled
                            "open"      -> ObligationStatus.Open
                            else        -> ObligationStatus.Open
                        },
                    )
                }
            ApiResult.Success(obligations)
        } catch (e: Exception) {
            val msg = "Failed to parse obligations response: ${e.message}"
            logger.error(e) { msg }
            ApiResult.Failure(msg, e)
        }
    }
}
