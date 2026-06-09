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