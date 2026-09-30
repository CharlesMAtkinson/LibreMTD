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

import org.charlesatkinson.libremtd.ui.ObligationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure tests for [buildObligation] — no HTTP involved. These exist
 * specifically to prove the fix for the bug where the tax year assumed
 * for a period was taken from the caller's request rather than the
 * obligation's own dates, and where the database's periodKey column could
 * end up holding a full display label instead of a short HMRC code.
 */
class ObligationsClientTest {

    private fun detail(
        periodStartDate: String,
        periodEndDate:   String,
        dueDate:         String = "2026-08-05",
        status:          String = "Open",
        periodKey:       String? = null,
    ) = ObligationDetail(
        status          = status,
        periodStartDate = periodStartDate,
        periodEndDate   = periodEndDate,
        dueDate         = dueDate,
        periodKey       = periodKey,
    )

    @Test
    fun `taxYear is derived from the obligation's own start date, not assumed from context`() {
        // This is the exact case from the bug report: HMRC's sandbox
        // returned an obligation dated in 2018-19 in response to a request
        // for 2026-27. taxYear must reflect the dates actually returned.
        val obligation = buildObligation(
            detail(periodStartDate = "2018-04-06", periodEndDate = "2018-07-05")
        )

        assertEquals("2018-19", obligation.taxYear)
    }

    @Test
    fun `periodKey is the short HMRC code, not the display label`() {
        val obligation = buildObligation(
            detail(periodStartDate = "2026-04-06", periodEndDate = "2026-07-05", periodKey = "#001")
        )

        assertEquals("#001", obligation.periodKey)
        assertTrue(obligation.periodKey.length < 10, "periodKey should be a short code, not a label")
    }

    @Test
    fun `periodKey is derived from the start date when HMRC does not supply one`() {
        val obligation = buildObligation(
            detail(periodStartDate = "2026-10-06", periodEndDate = "2027-01-05", periodKey = null)
        )

        assertEquals("#003", obligation.periodKey)
    }

    @Test
    fun `displayLabel combines the derived tax year, period key and formatted dates`() {
        val obligation = buildObligation(
            detail(periodStartDate = "2026-04-06", periodEndDate = "2026-07-05", periodKey = "#001")
        )

        assertEquals("2026-27  #001  6 Apr to 5 Jul", obligation.displayLabel)
    }

    @Test
    fun `status is mapped case-insensitively`() {
        assertEquals(
            ObligationStatus.Fulfilled,
            buildObligation(detail("2026-04-06", "2026-07-05", status = "Fulfilled")).status,
        )
        assertEquals(
            ObligationStatus.Open,
            buildObligation(detail("2026-04-06", "2026-07-05", status = "open")).status,
        )
    }

    @Test
    fun `unparseable dates fall back without throwing, and report a blank tax year`() {
        val obligation = buildObligation(
            detail(periodStartDate = "not-a-date", periodEndDate = "also-not-a-date", periodKey = "#001")
        )

        assertEquals("", obligation.taxYear)
        assertEquals("#001", obligation.periodKey)
    }
}
