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

package org.charlesatkinson.libremtd.ui.components

import java.time.LocalDate

/** Returns the tax year start date, e.g. "2025-26" → "2025-04-06". */
fun taxYearStart(taxYear: String): String =
    "${taxYear.take(4)}-04-06"

/** Returns the tax year end date, e.g. "2025-26" → "2026-04-05". */
fun taxYearEnd(taxYear: String): String =
    "20${taxYear.takeLast(2)}-04-05"

/**
 * Returns the end date of the latest quarter whose end date is on or before
 * today, or null if the first quarter has not yet ended.
 *
 * Standard UK tax year quarters (6-Apr start):
 *   Q1  6 Apr – 5 Jul    due 7 Aug
 *   Q2  6 Jul – 5 Oct    due 7 Nov
 *   Q3  6 Oct – 5 Jan    due 7 Feb
 *   Q4  6 Jan – 5 Apr    due 7 May
 */
fun latestEndedQuarterDate(taxYear: String): String? {
    val startYear = taxYear.take(4).toInt()
    val quarters = listOf(
        "$startYear-07-05",
        "$startYear-10-05",
        "${startYear + 1}-01-05",
        "${startYear + 1}-04-05",
    )
    val today = LocalDate.now()
    return quarters.lastOrNull { LocalDate.parse(it) <= today }
}