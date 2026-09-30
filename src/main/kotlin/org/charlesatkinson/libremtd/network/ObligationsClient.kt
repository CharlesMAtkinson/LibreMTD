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
import mu.KotlinLogging
import org.charlesatkinson.libremtd.ui.Obligation
import org.charlesatkinson.libremtd.ui.ObligationStatus
import org.charlesatkinson.libremtd.utils.ApiResult
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private val logger = KotlinLogging.logger {}

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
 *
 * internal (not private) so ObligationsClientTest can exercise it directly.
 */
internal fun derivePeriodKey(startDate: LocalDate): String {
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
 *
 * internal (not private) so ObligationsClientTest can exercise it directly.
 */
internal fun deriveTaxYear(startDate: LocalDate): String {
    val m = startDate.monthValue
    val d = startDate.dayOfMonth
    val y = startDate.year
    val startYear = if (m > 4 || (m == 4 && d >= 6)) y else y - 1
    return "$startYear-${(startYear + 1).toString().takeLast(2)}"
}

/**
 * Builds an [Obligation] from one HMRC [ObligationDetail].
 *
 * The tax year and period key are both derived from the obligation's OWN
 * dates, never assumed from the tax year the caller happened to request
 * obligations for. This matters because HMRC's sandbox test data does not
 * necessarily respect the fromDate/toDate query parameters — it can return
 * obligations dated in a completely different tax year to the one
 * requested — and previously LibreMTD stored whatever tax year the pane
 * was currently showing alongside those dates regardless, producing a
 * Period row whose taxYear and startDate/endDate disagreed with each
 * other.
 *
 * [Obligation.periodKey] holds the short HMRC code (e.g. "#001"), suitable
 * for storing in the database. [Obligation.displayLabel] holds the
 * human-readable summary shown in the Obligations table
 * ("2026-27  #001  6 Apr to 5 Jul"). These were previously conflated into
 * a single field, which is what caused the full display text to be
 * written into the database's periodKey column.
 *
 * internal (not private) so ObligationsClientTest can exercise it directly,
 * without needing to mock an HTTP response.
 */
internal fun buildObligation(detail: ObligationDetail): Obligation {
    val status = when (detail.status.lowercase()) {
        "fulfilled" -> ObligationStatus.Fulfilled
        "open"      -> ObligationStatus.Open
        else        -> ObligationStatus.Open
    }

    return try {
        val start   = LocalDate.parse(detail.periodStartDate)
        val end     = LocalDate.parse(detail.periodEndDate)
        val taxYear = deriveTaxYear(start)
        val key     = detail.periodKey ?: derivePeriodKey(start)

        Obligation(
            periodKey    = key,
            displayLabel = "$taxYear  $key  ${start.format(shortDateFmt)} to ${end.format(shortDateFmt)}",
            taxYear      = taxYear,
            start        = detail.periodStartDate,
            end          = detail.periodEndDate,
            due          = detail.dueDate,
            status       = status,
        )
    } catch (_: DateTimeParseException) {
        // Dates didn't parse — fall back to something that displays without
        // crashing. taxYear is deliberately left blank so a caller that
        // persists obligations to the Periods table knows to skip this one
        // rather than write a row with an unknown tax year.
        val fallbackKey = detail.periodKey ?: detail.periodStartDate
        Obligation(
            periodKey    = fallbackKey,
            displayLabel = fallbackKey,
            taxYear      = "",
            start        = detail.periodStartDate,
            end          = detail.periodEndDate,
            due          = detail.dueDate,
            status       = status,
        )
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
                .map { detail -> buildObligation(detail) }
            ApiResult.Success(obligations)
        } catch (e: Exception) {
            val msg = "Failed to parse obligations response: ${e.message}"
            logger.error(e) { msg }
            ApiResult.Failure(msg, e)
        }
    }
}
