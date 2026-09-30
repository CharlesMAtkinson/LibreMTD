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

package org.charlesatkinson.libremtd.ui

/**
 * One HMRC quarterly obligation.
 *
 * [periodKey] is the short HMRC code for this period, e.g. "#001" — this is
 * what gets stored in the database's Periods table.
 *
 * [displayLabel] is the fuller human-readable summary shown in the
 * Obligations table, e.g. "2026-27  #001  6 Apr to 5 Jul". Kept separate
 * from [periodKey] so that persisting an obligation to the database can
 * never accidentally store the display text instead of the actual code —
 * see network.buildObligation's doc comment for the bug this fixes.
 *
 * [taxYear] is the tax year this obligation's OWN dates fall in, derived
 * from [start] rather than assumed from whatever tax year the caller
 * requested obligations for. HMRC's sandbox test data does not necessarily
 * respect the requested date range, so this can legitimately differ from
 * the tax year currently being viewed in the Submissions pane.
 */
data class Obligation(
    val periodKey:    String,
    val displayLabel: String,
    val taxYear:      String,
    val start:        String,
    val end:          String,
    val due:          String,
    val status:       ObligationStatus,
)

enum class ObligationStatus { Open, Fulfilled, Overdue }
