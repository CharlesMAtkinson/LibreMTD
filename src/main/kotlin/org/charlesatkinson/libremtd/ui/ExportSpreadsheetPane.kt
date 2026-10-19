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

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.stage.FileChooser
import kotlinx.coroutines.CoroutineScope
import org.charlesatkinson.libremtd.database.Property
import org.charlesatkinson.libremtd.database.PropertyRepository
import org.charlesatkinson.libremtd.database.PropertyType
import org.charlesatkinson.libremtd.database.availableTaxYears
import org.charlesatkinson.libremtd.fileio.SpreadsheetExporter
import org.charlesatkinson.libremtd.fileio.components.ExportTable
import org.charlesatkinson.libremtd.fileio.components.sheetSpecFor
import org.charlesatkinson.libremtd.fileio.propertyDisplayString
import org.charlesatkinson.libremtd.ui.components.TaxYearItem
import org.charlesatkinson.libremtd.ui.components.UiPreferences
import org.charlesatkinson.libremtd.ui.components.countInWords
import org.charlesatkinson.libremtd.ui.components.wrappingLabel
import java.io.File

class ExportSpreadsheetPane(
    private val scope: CoroutineScope,
    private val userId: Int,
    private val onStatusChange: (String) -> Unit,
) {
    val root: VBox
    private val prefs = UiPreferences(userId)

    // ── Table picker ───────────────────────────────────────────────────────

    private data class TableChoice(
        val table:   ExportTable?,   // null = all tables
        val uiLabel: String,
    ) {
        override fun toString() = uiLabel
    }

    private val tableChoices: List<TableChoice> =
        ExportTable.entries.map { TableChoice(it, it.label) } +
                TableChoice(null, "All tables (one file, ${countInWords(ExportTable.entries.size)} sheets)")

    private val tablePicker = ComboBox<TableChoice>().apply {
        items.addAll(tableChoices)
        value    = tableChoices[0]
        maxWidth = Double.MAX_VALUE
    }

    // ── Tax year selector ─────────────────────────────────────────────────

    private val taxYearCombo = ComboBox<TaxYearItem>().apply {
        maxWidth   = Double.MAX_VALUE
        promptText = "Select tax year…"
    }
    private val taxYearRow = HBox(8.0, wrappingLabel("Tax year:"), taxYearCombo).apply {
        alignment = Pos.CENTER_LEFT
        HBox.setHgrow(taxYearCombo, Priority.ALWAYS)
    }

    // ── Property filter: only for sheets with a property column ───────────

    private data class PropertyItem(val id: Int?, val label: String) {
        override fun toString() = label
    }

    private val propertyCombo = ComboBox<PropertyItem>().apply {
        maxWidth   = Double.MAX_VALUE
        promptText = "All properties"
    }
    private val propertyRow = HBox(8.0, wrappingLabel("Property:"), propertyCombo).apply {
        alignment = Pos.CENTER_LEFT
        HBox.setHgrow(propertyCombo, Priority.ALWAYS)
    }

    private var userProperties: List<Property> = emptyList()

    // True while the property combo is being repopulated by code, so the
    // programmatic selection does not overwrite the saved preference.
    private var refreshingProperties = false

    // ── Status / export button ────────────────────────────────────────────

    private val statusLabel = wrappingLabel("").apply {
        styleClass.add("status-label")
    }

    private val exportButton = Button("Export to .xlsx spreadsheet").apply {
        styleClass.add("primary-action-button")
        maxWidth = Double.MAX_VALUE
    }

    // ── Init ──────────────────────────────────────────────────────────────

    init {
        root = buildUI()

        userProperties = PropertyRepository.findByUser(userId)
        loadTaxYears()
        restoreSelections()

        // The change handlers are installed only now. A ComboBox fires its
        // action on programmatic value changes as well as user ones, so
        // installing them earlier made the defaults set while loading
        // overwrite the saved preferences before they could be restored.
        tablePicker.setOnAction {
            tablePicker.value?.let { prefs.lastExportTable = it.table?.name }
            onTableSelected()
        }
        taxYearCombo.setOnAction {
            taxYearCombo.value?.let { prefs.lastTaxYear = it.value }
        }
        propertyCombo.setOnAction {
            if (!refreshingProperties) prefs.lastPropertyId = propertyCombo.value?.id
        }
        exportButton.setOnAction { onExport() }
    }

    // ── UI builder ────────────────────────────────────────────────────────

    private fun buildUI(): VBox {
        val formGrid = GridPane().apply {
            hgap = 12.0
            vgap = 8.0
            columnConstraints.addAll(
                ColumnConstraints(120.0),
                ColumnConstraints().also { it.hgrow = Priority.ALWAYS },
            )
            add(Label("Table:"), 0, 0)
            add(tablePicker,     1, 0)
        }

        return VBox(16.0).apply {
            padding = Insets(24.0)
            children.addAll(
                wrappingLabel("Export to .xlsx spreadsheet").apply {
                    style = "-fx-font-size: 22px; -fx-font-weight: bold;"
                },
                wrappingLabel(
                    "Export entries to a spreadsheet.  The spreadsheet can be changed and imported.  " +
                            "You may delete any sheets you do not need.  " +
                            "Rows which were exported have an id value.  " +
                            "You can delete those entries by deleting all other cells.  " +
                            "You can change those entries by changing other cell(s).  " +
                            "You can add records by creating new rows."
                ).apply {
                    style      = "-fx-text-fill: #555555;"
                    isWrapText = true
                },
                Separator(),
                formGrid,
                taxYearRow,
                propertyRow,
                Separator(),
                exportButton,
                statusLabel,
            )
        }
    }

    // ── Data loaders ──────────────────────────────────────────────────────

    private fun loadTaxYears() {
        val years = availableTaxYears().sortedDescending().map { TaxYearItem(it) }
        taxYearCombo.items.setAll(years)
        taxYearCombo.value = years.firstOrNull()   // default; overridden by restoreSelections()
    }

    /**
     * Restores the table and tax year selections from [prefs], falling back
     * to the defaults when nothing was saved or the saved value no longer
     * matches an item, then fills the property combo for the chosen table.
     */
    private fun restoreSelections() {
        prefs.lastExportTable?.let { saved ->
            tableChoices.firstOrNull { it.table?.name == saved }?.let { tablePicker.value = it }
        }
        prefs.lastTaxYear?.let { saved ->
            taxYearCombo.items.firstOrNull { it.value == saved }?.let { taxYearCombo.value = it }
        }
        onTableSelected()
    }

    // ── Selector visibility and contents ──────────────────────────────────

    private fun propertyTypesFor(choice: TableChoice?): Set<PropertyType> {
        val tables = if (choice?.table != null) listOf(choice.table) else ExportTable.entries
        return tables.mapNotNull { sheetSpecFor(it).propertyType }.toSet()
    }

    private fun onTableSelected() {
        refreshPropertyCombo()
        statusLabel.text = ""
    }

    /**
     * Lists only properties of the type(s) the chosen sheet(s) accept, and
     * hides the row when no chosen sheet has a property column.
     */
    private fun refreshPropertyCombo() {
        val types = propertyTypesFor(tablePicker.value)
        val allItem = PropertyItem(id = null, label = "All properties")
        val relevant = userProperties
            .filter { it.propertyType in types }
            .map { PropertyItem(it.id, propertyDisplayString(it.address, it.postcode, it.countryCode)) }

        refreshingProperties = true
        try {
            propertyCombo.items.setAll(listOf(allItem) + relevant)
            val savedId = prefs.lastPropertyId
            propertyCombo.value = relevant.firstOrNull { it.id == savedId } ?: allItem
        } finally {
            refreshingProperties = false
        }

        propertyRow.isVisible = types.isNotEmpty()
        propertyRow.isManaged = types.isNotEmpty()
    }

    // ── Export action ─────────────────────────────────────────────────────

    private fun onExport() {
        val choice  = tablePicker.value ?: run { setStatus("Please select a table.", error = true); return }
        val taxYear = taxYearCombo.value ?: run { setStatus("Please select a tax year.", error = true); return }

        val qualifier = taxYear.value.replace("-", "_")

        val chooser = FileChooser().apply {
            title = "Save .xlsx spreadsheet file"
            extensionFilters.add(
                FileChooser.ExtensionFilter("LibreOffice / Excel Spreadsheet (*.xlsx)", "*.xlsx")
            )
            prefs.lastExportDir?.let { initialDirectory = File(it) }
            initialFileName = if (choice.table != null)
                SpreadsheetExporter.suggestedFileName(choice.table, qualifier)
            else
                SpreadsheetExporter.suggestedAllFileName()
        }

        val file = chooser.showSaveDialog(root.scene?.window) ?: return
        prefs.lastExportDir = file.parent

        try {
            exportButton.isDisable = true
            setStatus("Exporting…")
            SpreadsheetExporter.exportTables(
                tables           = choice.table?.let { listOf(it) } ?: ExportTable.entries,
                userId           = userId,
                taxYear          = taxYear.value,
                filterPropertyId = propertyCombo.value?.id,
                outputPath       = file.toPath(),
            )
            setStatus("Exported: ${file.name}")
        } catch (ex: Exception) {
            setStatus("Export failed: ${ex.message}", error = true)
        } finally {
            exportButton.isDisable = false
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun setStatus(msg: String, error: Boolean = false) {
        statusLabel.text = msg
        statusLabel.styleClass.removeAll("status-error", "status-ok")
        statusLabel.styleClass.add(if (error) "status-error" else "status-ok")
        onStatusChange(msg)
    }
}
