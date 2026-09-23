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
    private lateinit var noPeriodsHintLabel: Label

    var selectedPeriod: Period? = null
        private set

    init {
        root = buildUI()
        loadPeriods()
    }

    private fun buildUI(): HBox {
        periodPicker.apply {
            prefWidth  = 320.0
            maxWidth   = Double.MAX_VALUE
            buttonCell = periodButtonCell()
            setCellFactory { periodCell() }
            setOnAction {
                selectedPeriod = value
                value?.let { prefs.lastPeriodId = it.id }
                onSelectionChanged(value)
            }
        }

        noPeriodsHintLabel = hintLabel("").apply {
            isVisible = false
            isManaged = false
        }

        return HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            padding   = Insets(0.0, 0.0, 4.0, 0.0)
            children.addAll(
                wrappingLabel("Period:").apply { prefWidth = 52.0 },
                periodPicker,
                noPeriodsHintLabel,
            )
        }
    }

    /**
     * Re-fetches periods from the database and re-applies the current or
     * most recently saved selection. Periods are only populated once
     * ObligationsSection.refresh() has fetched them from HMRC at least
     * once, so a selector built before that has nothing to offer — call
     * this after a successful HMRC connection to pick up periods that
     * didn't exist yet at construction time. See MainWindow.notifyConnected(),
     * which calls this indirectly via each hosting pane's refresh().
     */
    fun reload() = loadPeriods()

    private fun loadPeriods() {
        val periods = PeriodRepository.findAll()
        periodPicker.items.setAll(periods)

        // With no periods at all — e.g. straight after a fresh database
        // initialisation, before any obligations have ever been fetched
        // from HMRC — the picker previously looked identical to a broken
        // control: no text (see periodButtonCell's doc comment) and
        // nothing to select when clicked. Disabling it and explaining why
        // makes the actual state visible instead of silent.
        periodPicker.isDisable = periods.isEmpty()
        noPeriodsHintLabel.text =
            if (periods.isEmpty()) "No periods yet — fetch obligations from the Submissions pane first."
            else ""
        noPeriodsHintLabel.isVisible = periods.isEmpty()
        noPeriodsHintLabel.isManaged = periods.isEmpty()

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

    /**
     * The closed ComboBox's own displayed cell. Setting a custom buttonCell
     * like this one takes over responsibility for what's shown when
     * nothing is selected — JavaFX's normal promptText handling does not
     * apply once a buttonCell is set, so the placeholder text has to be
     * produced here explicitly. Previously this returned null text in that
     * case, which is why the picker appeared completely blank.
     */
    private fun periodButtonCell() = object : ListCell<Period>() {
        override fun updateItem(item: Period?, empty: Boolean) {
            super.updateItem(item, empty)
            text = when {
                !empty && item != null       -> formatPeriod(item)
                periodPicker.items.isEmpty() -> "No periods available"
                else                          -> "Select period"
            }
        }
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
