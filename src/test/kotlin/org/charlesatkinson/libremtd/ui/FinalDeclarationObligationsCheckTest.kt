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

package org.charlesatkinson.libremtd.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure function only: no JavaFX toolkit and no database.
 */
class FinalDeclarationObligationsCheckTest {

    private fun obligation(key: String, status: ObligationStatus) = Obligation(
        periodKey    = key,
        displayLabel = "2026-27  $key",
        taxYear      = "2026-27",
        start        = "2026-04-06",
        end          = "2026-07-05",
        due          = "2026-08-07",
        status       = status,
    )

    private fun allFulfilled() = listOf("#001", "#002", "#003", "#004").map { obligation(it, ObligationStatus.Fulfilled) }

    // ── not loaded ────────────────────────────────────────────────────────

    @Test
    fun `an empty list is a problem, not permission to go ahead`() {
        assertNotNull(obligationsProblem("2026-27", emptyList()))
    }

    @Test
    fun `the not loaded message names the tax year and says what to do`() {
        val problem = obligationsProblem("2026-27", emptyList())!!

        assertTrue(problem.message.contains("2026-27"))
        assertTrue(problem.message.contains("Refresh"))
        assertTrue(problem.message.contains("not be connected") || problem.message.contains("not connected"))
        assertTrue(problem.title.contains("Not Loaded"))
    }

    // ── not fulfilled ─────────────────────────────────────────────────────

    @Test
    fun `an open quarter is a problem and is named`() {
        val problem = obligationsProblem(
            "2026-27",
            allFulfilled().dropLast(1) + obligation("#004", ObligationStatus.Open),
        )!!

        assertTrue(problem.title.contains("Not Fulfilled"))
        assertTrue(problem.message.contains("1 quarter(s) are not yet fulfilled"))
        assertTrue(problem.message.contains("#004"))
        assertFalse(problem.message.contains("#001"))
    }

    @Test
    fun `an overdue quarter counts as not fulfilled`() {
        val problem = obligationsProblem("2026-27", listOf(obligation("#001", ObligationStatus.Overdue)))!!

        assertTrue(problem.message.contains("#001"))
    }

    @Test
    fun `every unfulfilled quarter is listed`() {
        val problem = obligationsProblem(
            "2026-27",
            listOf(
                obligation("#001", ObligationStatus.Fulfilled),
                obligation("#002", ObligationStatus.Open),
                obligation("#003", ObligationStatus.Overdue),
            ),
        )!!

        assertTrue(problem.message.contains("2 quarter(s) are not yet fulfilled"))
        assertTrue(problem.message.contains("#002"))
        assertTrue(problem.message.contains("#003"))
    }

    // ── fine ──────────────────────────────────────────────────────────────

    @Test
    fun `all quarters fulfilled is no problem`() {
        assertNull(obligationsProblem("2026-27", allFulfilled()))
    }

    @Test
    fun `a single fulfilled obligation is no problem`() {
        assertNull(obligationsProblem("2026-27", listOf(obligation("#001", ObligationStatus.Fulfilled))))
    }

    @Test
    fun `the two problems have different titles`() {
        val notLoaded = obligationsProblem("2026-27", emptyList())!!
        val open      = obligationsProblem("2026-27", listOf(obligation("#001", ObligationStatus.Open)))!!

        assertTrue(notLoaded.title != open.title)
    }

    @Test
    fun `the messages use no em-dashes`() {
        val all = listOf(
            obligationsProblem("2026-27", emptyList())!!,
            obligationsProblem("2026-27", listOf(obligation("#001", ObligationStatus.Open)))!!,
        )

        all.forEach {
            assertFalse(it.message.contains('\u2014'), it.message)
            assertFalse(it.title.contains('\u2014'), it.title)
        }
    }
}
