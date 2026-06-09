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
import javafx.scene.control.*
import javafx.scene.layout.*
import org.charlesatkinson.libremtd.database.Period
import org.charlesatkinson.libremtd.database.PeriodRepository
import org.charlesatkinson.libremtd.ui.components.UiPreferences
import java.time.LocalDate

class PeriodSelector(
    private val userId: Int,
    private val onSelectionChanged: (Period?) -> Unit,
) {
    private val prefs = UiPreferences(userId)
    val root: HBox

    private val periodPicker = ComboBox<Period>()

    var selectedPeriod: Period? = null
        private set

    init {
        root = buildUI()
        loadPeriods()
    }

    private fun buildUI(): HBox {
        periodPicker.apply {
            promptText = "Select period"
            prefWidth  = 320.0
            maxWidth   = Double.MAX_VALUE
            buttonCell = object : ListCell<Period>() {
                override fun updateItem(item: Period?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else formatPeriod(item)
                }
            }
            setCellFactory { periodCell() }
            setOnAction {
                selectedPeriod = value
                value?.let { prefs.lastPeriodId = it.id }
                onSelectionChanged(value)
            }
        }

        return HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            padding   = Insets(0.0, 0.0, 4.0, 0.0)
            children.addAll(
                wrappingLabel("Period:").apply { prefWidth = 52.0 },
                periodPicker,
            )
        }
    }

    private fun loadPeriods() {
        val periods = PeriodRepository.findAll()
        periodPicker.items.setAll(periods)

        val lastId = prefs.lastPeriodId
        val current = when {
            lastId != null -> periods.firstOrNull { it.id == lastId }
            else           -> null
        } ?: run {
            val today = LocalDate.now()
            periods.firstOrNull { period ->
                val start = LocalDate.parse(period.startDate)
                val end   = LocalDate.parse(period.endDate)
                !today.isBefore(start) && !today.isAfter(end)
            } ?: periods.firstOrNull()
        }

        periodPicker.value = current
        selectedPeriod     = current
        onSelectionChanged(current)
    }

    private fun periodCell() = object : ListCell<Period>() {
        override fun updateItem(item: Period?, empty: Boolean) {
            super.updateItem(item, empty)
            text = if (empty || item == null) null else formatPeriod(item)
        }
    }

    private fun formatPeriod(period: Period): String {
        val start = LocalDate.parse(period.startDate)
        val end   = LocalDate.parse(period.endDate)
        val fmt   = java.time.format.DateTimeFormatter.ofPattern("d MMM")
        return "${period.taxYear}  ${period.periodKey}  ${start.format(fmt)} to ${end.format(fmt)}"
    }
}