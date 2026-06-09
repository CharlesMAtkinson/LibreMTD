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

package org.charlesatkinson.libremtd.database

import java.time.LocalDate

/** Returns the current MTD tax year string, e.g. "2025-26". */
fun currentTaxYear(): String {
    val today = LocalDate.now()
    val year = if (today.monthValue > 4 || (today.monthValue == 4 && today.dayOfMonth >= 6))
        today.year else today.year - 1
    return "$year-${(year + 1).toString().takeLast(2)}"
}

/**
 * Returns a list of MTD tax year strings from [firstYear] up to and including
 * the current tax year, in ascending order.
 * [firstYear] should be the start year of the earliest supported tax year,
 * e.g. 2023 for "2023-24".
 */
fun availableTaxYears(firstYear: Int = 2025): List<String> {
    val today = LocalDate.now()
    val currentStartYear = if (today.monthValue > 4 || (today.monthValue == 4 && today.dayOfMonth >= 6))
        today.year else today.year - 1
    return (firstYear..currentStartYear).map { y ->
        "$y-${(y + 1).toString().takeLast(2)}"
    }
}

/** "2025-26" → Pair("2025-04-06", "2026-04-05") */
fun taxYearDateRange(taxYear: String): Pair<String, String> {
    val startYear = taxYear.take(4).toInt()
    return "${startYear}-04-06" to "${startYear + 1}-04-05"
}

/**
 * Returns the MTD tax year string (e.g. "2025-26") for a given ISO date string.
 * Tax year runs 6 April to 5 April inclusive.
 */
fun taxYearForDate(isoDate: String): String {
    val date = LocalDate.parse(isoDate)
    val startYear = if (date.monthValue > 4 || (date.monthValue == 4 && date.dayOfMonth >= 6))
        date.year
    else
        date.year - 1
    return "$startYear-${(startYear + 1).toString().takeLast(2)}"
}