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

package org.charlesatkinson.libremtd.ui.components

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.ComboBox
import javafx.scene.layout.HBox
import org.charlesatkinson.libremtd.database.availableTaxYears
import org.charlesatkinson.libremtd.database.currentTaxYear
import org.charlesatkinson.libremtd.ui.components.UiPreferences

// ── Standalone functions (kept for callers that still need them) ──────────────

/**
 * The most recently *completed* tax year — the penultimate entry from
 * [availableTaxYears], falling back to the last if only one exists.
 */
fun previousCompletedTaxYear(): String {
    val years = availableTaxYears()
    // Potential gotcha: years.last may be a still-open tax year
    return if (years.size >= 2) years[years.size - 2] else years.last()
}

/**
 * Builds the error message shown when a transaction date falls in a tax
 * year other than the one currently being viewed (or, with [editing] set,
 * the one an in-progress edit belongs to).
 *
 * Distinguishes three cases:
 *
 * - [derivedTaxYear] is a year LibreMTD supports: invites the user to
 *   switch views or correct the date, as before.
 * - [derivedTaxYear] is earlier than the earliest supported year (e.g.
 *   2025-26 or earlier — see database.availableTaxYears' doc comment):
 *   says so plainly and does not suggest switching to it, since there is
 *   nowhere to switch to.
 * - [derivedTaxYear] is later than the latest currently-offered year: this
 *   is a genuinely different situation from "too early" — the tax year
 *   simply has not started yet, going by today's date — so it gets its
 *   own wording rather than reusing "supported tax years start from …",
 *   which would be true but misleading here.
 */
fun wrongTaxYearMessage(
    dateText: String,
    derivedTaxYear: String,
    currentTaxYear: String,
    editing: Boolean = false,
): String {
    val years = availableTaxYears()

    if (derivedTaxYear in years) {
        val viewingClause = if (editing)
            "editing an entry in $currentTaxYear"
        else
            "viewing $currentTaxYear"
        val actionClause = if (editing)
            "Correct the transaction date, or cancel the edit and switch to the $derivedTaxYear view instead."
        else
            "Switch to the $derivedTaxYear view, or correct the date."

        return "The transaction date $dateText falls in tax year $derivedTaxYear, but you are " +
                "$viewingClause.\n\n$actionClause"
    }

    return if (derivedTaxYear < years.first()) {
        "The transaction date $dateText falls in tax year $derivedTaxYear, which LibreMTD does not " +
                "support (supported tax years start from ${years.first()}).\n\n" +
                "Please correct the date."
    } else {
        "The transaction date $dateText falls in tax year $derivedTaxYear, which has not started yet " +
                "(the most recent tax year LibreMTD currently offers is ${years.last()}).\n\n" +
                "Please correct the date, or check the system date if this was unintended."
    }
}

// ── Shared component ──────────────────────────────────────────────────────────

/**
 * A reusable "Tax year:" label + [ComboBox] row.
 *
 * Pre-selects [prefs.lastTaxYear] when it is present in
 * [availableTaxYears], otherwise falls back to [fallback].
 * Every change is persisted to [prefs.lastTaxYear].
 *
 * @param fallback  Value to pre-select when there is no stored preference.
 *                  Defaults to [currentTaxYear].
 * @param onChange  Called with the newly-selected year whenever it changes
 *                  (including the initial selection during construction).
 */
class TaxYearSelector(
    private val userId: Int,
    fallback: String = currentTaxYear(),
    private val onChange: (String) -> Unit,
) {
    private val prefs = UiPreferences(userId)
    val root: HBox

    private val picker = ComboBox<String>()
    private val fallbackValue = fallback   // ← capture before constructor scope ends

    var value: String
        get() = picker.value ?: fallbackValue
        private set(v) { picker.value = v }

    init {
        val years = availableTaxYears()
        val stored = prefs.lastTaxYear
        val initial = if (stored != null && stored in years) stored else fallback

        picker.apply {
            items.setAll(years)
            value = initial
            setOnAction {
                val selected = value ?: return@setOnAction
                prefs.lastTaxYear = selected
                onChange(selected)
            }
        }

        root = HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            padding   = Insets(0.0, 0.0, 8.0, 0.0)
            children.addAll(
                wrappingLabel("Tax year:").apply { prefWidth = 70.0 },
                picker,
            )
        }

        // Fire once so the host pane initialises with the pre-selected value.
        prefs.lastTaxYear = initial
        onChange(initial)
    }
}
