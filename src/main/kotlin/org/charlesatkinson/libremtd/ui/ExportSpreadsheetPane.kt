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
import org.charlesatkinson.libremtd.database.PropertyRepository
import org.charlesatkinson.libremtd.database.availableTaxYears
import org.charlesatkinson.libremtd.fileio.ExportTable
import org.charlesatkinson.libremtd.fileio.PropertyDisplay
import org.charlesatkinson.libremtd.fileio.SpreadsheetExporter
import org.charlesatkinson.libremtd.fileio.fetchRows
import org.charlesatkinson.libremtd.fileio.incomePropertyColumns
import org.charlesatkinson.libremtd.fileio.propertyDisplayString
import org.charlesatkinson.libremtd.ui.components.TaxYearItem
import org.charlesatkinson.libremtd.ui.components.UiPreferences
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

    private val tableChoices = listOf(
        TableChoice(ExportTable.INCOME_PROPERTY,  "Income (property)"),
        TableChoice(ExportTable.INCOME_DIVIDENDS, "Income (dividends)"),
        TableChoice(ExportTable.EXPENSES,         "Expenses"),
        TableChoice(ExportTable.INCOME_SAVINGS,   "Income (savings)"),
        TableChoice(null,                         "All tables (one file, four sheets)"),
    )

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

    // ── Property filter — INCOME_PROPERTY and EXPENSES only ───────────────

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

    // ── Cached property data ──────────────────────────────────────────────

    private var propertyDisplayList: List<PropertyDisplay> = emptyList()

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

        // Persist selections whenever the user changes them.
        tablePicker.setOnAction {
            tablePicker.value?.let { prefs.lastExportTable = it.table?.name }
            onTableSelected()
        }
        taxYearCombo.setOnAction {
            taxYearCombo.value?.let { prefs.lastTaxYear = it.value }
        }
        propertyCombo.setOnAction {
            // null id = "All properties" sentinel; store null to clear the key.
            prefs.lastPropertyId = propertyCombo.value?.id
        }

        exportButton.setOnAction { onExport() }

        loadProperties()   // must come before restoreSelections()
        loadTaxYears()
        restoreSelections()
        onTableSelected()
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
                wrappingLabel("Export entries to a spreadsheet.  Can be modified and imported back.").apply {
                    style = "-fx-text-fill: #555555;"
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

    private fun loadProperties() {
        propertyDisplayList = PropertyRepository.findByUser(userId).map { p ->
            PropertyDisplay(
                id      = p.id,
                display = propertyDisplayString(p.address, p.postcode),
            )
        }

        val allItem = PropertyItem(id = null, label = "All properties")
        val items   = listOf(allItem) + propertyDisplayList.map { PropertyItem(it.id, it.display) }
        propertyCombo.items.setAll(items)
        propertyCombo.value = allItem   // default; overridden by restoreSelections()
    }

    private fun loadTaxYears() {
        val years = availableTaxYears().sortedDescending().map { TaxYearItem(it) }
        taxYearCombo.items.setAll(years)
        taxYearCombo.value = years.firstOrNull()   // default; overridden by restoreSelections()
    }

    /**
     * Restores the table, tax year, and property selections from
     * [prefs], falling back to sensible defaults when no saved
     * value exists or the saved value no longer matches any current item.
     *
     * Must be called after [loadProperties] and [loadTaxYears].
     */
    private fun restoreSelections() {
        // Table
        val savedTable = prefs.lastExportTable
        if (savedTable != null) {
            tableChoices.firstOrNull { it.table?.name == savedTable }
                ?.let { tablePicker.value = it }
        }

        // Tax year
        val savedYear = prefs.lastTaxYear
        if (savedYear != null) {
            taxYearCombo.items.firstOrNull { it.value == savedYear }
                ?.let { taxYearCombo.value = it }
        }

        // Property — uses the shared lastPropertyId key so the selection is
        // consistent with other panes that have a property selector.
        val savedPropertyId = prefs.lastPropertyId
        if (savedPropertyId != null) {
            propertyCombo.items.firstOrNull { it.id == savedPropertyId }
                ?.let { propertyCombo.value = it }
        }
        // If savedPropertyId is null the default "All properties" item set in
        // loadProperties() stands.
    }

    // ── Selector visibility ───────────────────────────────────────────────

    private fun onTableSelected() {
        val choice = tablePicker.value ?: return
        propertyRow.isVisible = choice.table == ExportTable.INCOME_PROPERTY ||
                choice.table == ExportTable.EXPENSES        ||
                choice.table == null
        statusLabel.text = ""
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
            if (choice.table != null) exportSingleTable(choice.table, taxYear.value, file)
            else                      exportAllTables(taxYear.value, file)
            setStatus("Exported: ${file.name}")
        } catch (ex: Exception) {
            setStatus("Export failed: ${ex.message}", error = true)
        } finally {
            exportButton.isDisable = false
        }
    }

    private fun exportSingleTable(table: ExportTable, taxYear: String, file: File) {
        val filterPropertyId   = propertyCombo.value?.id
        val propertyDisplayMap = propertyDisplayList.associate { it.id to it.display }

        val cols = when (table) {
            ExportTable.INCOME_PROPERTY ->
                incomePropertyColumns(propertyDisplayList.map { it.display })
            else ->
                SpreadsheetExporter.staticColumnsFor(table)
        }

        val rows = fetchRows(
            table              = table,
            userId             = userId,
            taxYear            = taxYear,
            filterPropertyId   = filterPropertyId,
            propertyDisplayMap = propertyDisplayMap,
        )

        SpreadsheetExporter.exportSingle(table, cols, rows, file.toPath())
    }

    private fun exportAllTables(taxYear: String, file: File) {
        val filterPropertyId       = propertyCombo.value?.id
        val propertyDisplayMap     = propertyDisplayList.associate { it.id to it.display }
        val propertyDisplayStrings = propertyDisplayList.map { it.display }

        val entries = mapOf(
            ExportTable.INCOME_PROPERTY to Pair(
                incomePropertyColumns(propertyDisplayStrings),
                fetchRows(ExportTable.INCOME_PROPERTY, userId, taxYear, filterPropertyId, propertyDisplayMap),
            ),
            ExportTable.INCOME_DIVIDENDS to Pair(
                SpreadsheetExporter.staticColumnsFor(ExportTable.INCOME_DIVIDENDS),
                fetchRows(ExportTable.INCOME_DIVIDENDS, userId, taxYear),
            ),
            ExportTable.EXPENSES to Pair(
                SpreadsheetExporter.staticColumnsFor(ExportTable.EXPENSES),
                fetchRows(ExportTable.EXPENSES, userId, taxYear, filterPropertyId),
            ),
            ExportTable.INCOME_SAVINGS to Pair(
                SpreadsheetExporter.staticColumnsFor(ExportTable.INCOME_SAVINGS),
                fetchRows(ExportTable.INCOME_SAVINGS, userId, taxYear),
            ),
        )

        SpreadsheetExporter.exportAll(entries, file.toPath())
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun setStatus(msg: String, error: Boolean = false) {
        statusLabel.text = msg
        statusLabel.styleClass.removeAll("status-error", "status-ok")
        statusLabel.styleClass.add(if (error) "status-error" else "status-ok")
        onStatusChange(msg)
    }
}
