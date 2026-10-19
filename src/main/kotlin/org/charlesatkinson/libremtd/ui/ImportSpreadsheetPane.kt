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

import javafx.beans.property.SimpleStringProperty
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.stage.FileChooser
import javafx.stage.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.charlesatkinson.libremtd.database.availableTaxYears
import org.charlesatkinson.libremtd.fileio.ConflictRow
import org.charlesatkinson.libremtd.fileio.ImportResult
import org.charlesatkinson.libremtd.fileio.SpreadsheetImporter
import org.charlesatkinson.libremtd.ui.components.TaxYearItem
import org.charlesatkinson.libremtd.ui.components.UiPreferences
import org.charlesatkinson.libremtd.ui.components.countInWords
import org.charlesatkinson.libremtd.ui.components.wrappingLabel
import org.slf4j.LoggerFactory
import java.io.File

/**
 * @param onImported Called on the JavaFX thread after every successful
 *                   import, so the host can discard cached panes which
 *                   would otherwise keep showing entries as they were
 *                   before the import changed the database.
 */
class ImportSpreadsheetPane(
    private val scope: CoroutineScope,
    private val userId: Int,
    private val onStatusChange: (String) -> Unit,
    private val onImported: () -> Unit = {},
) {
    private val prefs = UiPreferences(userId)
    val root: VBox

    // ── Logging ───────────────────────────────────────────────────────────

    private val log = LoggerFactory.getLogger(ImportSpreadsheetPane::class.java)

    // ── Tax year selector ─────────────────────────────────────────────────

    private val taxYearCombo = ComboBox<TaxYearItem>().apply {
        maxWidth   = Double.MAX_VALUE
        promptText = "Select tax year…"
    }
    private val taxYearRow = HBox(8.0, wrappingLabel("Tax year:"), taxYearCombo).apply {
        alignment = Pos.CENTER_LEFT
        HBox.setHgrow(taxYearCombo, Priority.ALWAYS)
    }

    // ── Status / button ───────────────────────────────────────────────────

    private val statusLabel = wrappingLabel("").apply {
        isWrapText = true
    }

    private val importButton = Button("Select .xlsx file and import…").apply {
        styleClass.add("primary-action-button")
        maxWidth = Double.MAX_VALUE
    }

    // ── Init ──────────────────────────────────────────────────────────────

    init {
        root = buildUI()

        // Loaded before the change handler is installed: a ComboBox fires
        // its action on programmatic changes too, which would otherwise
        // overwrite the saved preference while it is being restored.
        loadTaxYears()

        taxYearCombo.setOnAction {
            taxYearCombo.value?.let { prefs.lastTaxYear = it.value }
        }

        importButton.setOnAction { onImport() }
    }

    // ── UI builder ────────────────────────────────────────────────────────

    private fun buildUI(): VBox =
        VBox(16.0).apply {
            padding = Insets(24.0)
            children.addAll(
                wrappingLabel("Import from .xlsx spreadsheet").apply {
                    style = "-fx-font-size: 22px; -fx-font-weight: bold;"
                },
                wrappingLabel(
                    "Import entries from a spreadsheet previously exported by LibreMTD.  " +
                            "Either the whole file is imported or nothing is.  " +
                            "Every date must fall inside the selected tax year, which must not " +
                            "have been finally declared."
                ).apply {
                    style      = "-fx-text-fill: #555555;"
                    isWrapText = true
                },
                Separator(),
                taxYearRow,
                Separator(),
                importButton,
                statusLabel,
            )
        }

    // ── Data loaders ──────────────────────────────────────────────────────

    private fun loadTaxYears() {
        val years = availableTaxYears().sortedDescending().map { TaxYearItem(it) }
        taxYearCombo.items.setAll(years)

        val lastYear = prefs.lastTaxYear
        taxYearCombo.value = when {
            lastYear != null -> years.firstOrNull { it.value == lastYear } ?: years.firstOrNull()
            else             -> years.firstOrNull()
        }
    }

    // ── Import action ─────────────────────────────────────────────────────

    private fun onImport() {
        val taxYear = taxYearCombo.value ?: run { setStatus("Please select a tax year.", error = true); return }

        val chooser = FileChooser().apply {
            title = "Select .xlsx spreadsheet to import"
            extensionFilters.add(
                FileChooser.ExtensionFilter("LibreOffice / Excel Spreadsheet (*.xlsx)", "*.xlsx")
            )
            prefs.lastExportDir?.let { initialDirectory = File(it) }
        }

        val file = chooser.showOpenDialog(root.scene?.window) ?: return
        prefs.lastExportDir = file.parent

        importButton.isDisable = true
        setStatus("Validating and importing…")

        scope.launch(Dispatchers.IO) {
            val result = try {
                SpreadsheetImporter.import(file.toPath(), userId, taxYear.value)
            } catch (ex: Exception) {
                log.error("Import failed unexpectedly", ex)
                ImportResult.Failure(listOf("Import failed: ${ex.message ?: ex.javaClass.simpleName}"))
            }

            withContext(Dispatchers.JavaFx) {
                importButton.isDisable = false
                handleResult(result)
            }
        }
    }

    // ── Result handling ───────────────────────────────────────────────────

    private fun handleResult(result: ImportResult) {
        when (result) {
            is ImportResult.Success -> {
                val s   = result.summary
                val parts = mutableListOf(
                    "${s.inserted} inserted",
                    "${s.superseded} superseded",
                    "${s.deleted} deleted",
                    "${s.unchanged} unchanged",
                )
                if (s.skipped > 0) parts += "${s.skipped} skipped as duplicates"
                val msg = buildString {
                    append("Import complete – ")
                    append(parts.joinToString(", "))
                    append(".")
                    if (s.warnings.isNotEmpty()) {
                        append("\nWarnings:\n")
                        append(s.warnings.joinToString("\n"))
                    }
                }
                setStatus(msg, error = false)
                onImported()
            }

            is ImportResult.Failure -> {
                setStatus("Import failed:\n" + result.errors.joinToString("\n"), error = true)
            }

            is ImportResult.ConflictsFound -> {
                showConflictDialog(result.conflicts, result.proceed, result.proceedSkippingDuplicates)
            }
        }
    }

    // ── Conflict dialog ───────────────────────────────────────────────────

    /**
     * Shows a resizable, copyable table of conflicting rows and asks the
     * user whether to cancel, import everything except the duplicates, or
     * insert the duplicates as additional records.
     *
     * The chosen import runs on a background thread so the UI remains
     * responsive.
     */
    private fun showConflictDialog(
        conflicts: List<ConflictRow>,
        proceed: () -> ImportResult,
        proceedSkippingDuplicates: () -> ImportResult,
    ) {
        val dialog = Dialog<ButtonType>().apply {
            title      = "Possible duplicate rows found"
            headerText = null
            isResizable = true
        }

        // Copy the application icons from the owner window so the dialog
        // shows the LibreMTD icon rather than the generic JavaFX icon.
        val ownerWindow = root.scene?.window
        if (ownerWindow is Stage) {
            dialog.dialogPane.sceneProperty().addListener { _, _, newScene ->
                (newScene?.window as? Stage)?.icons?.setAll(ownerWindow.icons)
            }
        }

        fun column(title: String, prefWidth: Double, minWidth: Double, text: (ConflictRow) -> String) =
            TableColumn<ConflictRow, String>(title).also { col ->
                col.cellValueFactory = javafx.util.Callback { cdf -> SimpleStringProperty(text(cdf.value)) }
                col.prefWidth = prefWidth
                col.minWidth  = minWidth
            }

        // ── Resizable conflict table ───────────────────────────────────────
        // Every column keeps its preferred width and only the last one takes
        // up whatever width is left, so widening the window widens "Details".
        val table = TableView<ConflictRow>().apply {
            isEditable         = false
            columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
            prefHeight         = 300.0
            maxWidth           = Double.MAX_VALUE

            columns.addAll(
                column("Sheet",          215.0, 150.0) { it.sheetName },
                column("Sheet row",       90.0,  90.0) { it.sheetRowNum.toString() },
                column("Existing DB id", 115.0, 115.0) { it.existingDbId.toString() },
                column("Details",        300.0, 200.0) { it.summary },
            )

            items.setAll(conflicts)
        }
        VBox.setVgrow(table, Priority.ALWAYS)

        // Ctrl+C copies selected rows as tab-separated text.
        table.setOnKeyPressed { e ->
            if (e.isControlDown && e.code == javafx.scene.input.KeyCode.C) {
                val selected = table.selectionModel.selectedItems
                if (selected.isNotEmpty()) {
                    val text = selected.joinToString("\n") { r ->
                        listOf(r.sheetName, r.sheetRowNum.toString(), r.existingDbId.toString(), r.summary)
                            .joinToString("\t")
                    }
                    javafx.scene.input.Clipboard.getSystemClipboard().setContent(
                        javafx.scene.input.ClipboardContent().also { it.putString(text) }
                    )
                }
            }
        }

        val count = countInWords(conflicts.size).replaceFirstChar { it.uppercase() }
        val explanation = "$count ${if (conflicts.size == 1) "row" else "rows"} in the spreadsheet " +
                "may already exist in the database.  They have no id but match existing records " +
                "on all data fields.\n\n" +
                "\"Skip duplicates and import\" imports everything except these rows.  " +
                "\"Import duplicates too\" inserts them as additional records.  " +
                "Cancel imports nothing."

        val cancelButton = ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE)
        val skipButton   = ButtonType("Skip duplicates and import", ButtonBar.ButtonData.OK_DONE)
        val insertButton = ButtonType("Import duplicates too", ButtonBar.ButtonData.OTHER)
        dialog.dialogPane.buttonTypes.addAll(cancelButton, skipButton, insertButton)

        dialog.dialogPane.content = VBox(8.0,
            wrappingLabel(explanation).apply { maxWidth = Double.MAX_VALUE },
            wrappingLabel("Ctrl+C copies selected rows to the clipboard.  Right-click the text above to copy it.").apply {
                style = "-fx-text-fill: #555555; -fx-font-size: 11px;"
            },
            table,
        ).also { it.padding = Insets(8.0) }

        dialog.dialogPane.minWidth   = 640.0
        dialog.dialogPane.prefWidth  = 900.0
        dialog.dialogPane.prefHeight = 480.0

        val chosen = dialog.showAndWait().orElse(cancelButton)
        val action = when (chosen) {
            skipButton   -> proceedSkippingDuplicates
            insertButton -> proceed
            else         -> {
                setStatus("Import cancelled.", error = false)
                return
            }
        }

        // User confirmed: run the import on a background thread.
        importButton.isDisable = true
        setStatus("Importing…")
        scope.launch(Dispatchers.IO) {
            val result = try {
                action()
            } catch (ex: Exception) {
                log.error("Import failed after conflict dialog", ex)
                ImportResult.Failure(listOf("Import failed: ${ex.message ?: ex.javaClass.simpleName}"))
            }
            withContext(Dispatchers.JavaFx) {
                importButton.isDisable = false
                handleResult(result)
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun setStatus(msg: String, error: Boolean = false) {
        val firstLine     = msg.lineSequence().first()
        val statusBarText = if (firstLine.length <= STATUS_BAR_MAX_CHARS) firstLine
        else firstLine.take(STATUS_BAR_MAX_CHARS - 1) + "…"
        onStatusChange(statusBarText)

        val labelText = truncateLines(msg, LABEL_MAX_LINES)
        statusLabel.text  = labelText
        statusLabel.style = if (error) "-fx-text-fill: #c0392b;"
        else "-fx-text-fill: #1a6b3c; -fx-font-weight: bold;"

        val logText = truncateLines(msg, LOG_MAX_LINES)
        if (error) log.error(logText) else log.info(logText)
    }

    private fun truncateLines(fullText: String, maxLines: Int): String {
        val lines = fullText.lines()
        if (lines.size <= maxLines) return fullText
        val suppressed = lines.size - maxLines
        return (lines.take(maxLines) +
                "… ($suppressed further ${if (suppressed == 1) "line" else "lines"} suppressed)")
            .joinToString("\n")
    }

    companion object {
        private const val STATUS_BAR_MAX_CHARS = 120
        private const val LABEL_MAX_LINES      = 10
        private const val LOG_MAX_LINES        = 50
    }
}
