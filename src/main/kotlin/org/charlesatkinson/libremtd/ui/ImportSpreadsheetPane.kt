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
import org.charlesatkinson.libremtd.ui.components.wrappingLabel
import org.slf4j.LoggerFactory
import java.io.File

class ImportSpreadsheetPane(
    private val scope: CoroutineScope,
    private val userId: Int,
    private val onStatusChange: (String) -> Unit,
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

        taxYearCombo.setOnAction {
            taxYearCombo.value?.let { prefs.lastTaxYear = it.value }
        }

        importButton.setOnAction { onImport() }

        loadTaxYears()
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
                            "The table is inferred from the sheet name.  " +
                            "Rows which were exported have an id.  " +
                            "Rows with an id and all other cells blank are deleted.  " +
                            "Rows with an id and changed cell(s) are replaced.  " +
                            "Rows with no id are inserted as new records."
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
            val result = SpreadsheetImporter.import(file.toPath(), userId, taxYear.value)

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
                val msg = buildString {
                    append("Import complete — ")
                    append("${s.inserted} inserted, ")
                    append("${s.superseded} superseded, ")
                    append("${s.deleted} deleted, ")
                    append("${s.unchanged} unchanged.")
                    if (s.warnings.isNotEmpty()) {
                        append("\nWarnings:\n")
                        append(s.warnings.joinToString("\n"))
                    }
                }
                setStatus(msg, error = false)
            }

            is ImportResult.Failure -> {
                setStatus("Import failed:\n" + result.errors.joinToString("\n"), error = true)
            }

            is ImportResult.ConflictsFound -> {
                showConflictDialog(result.conflicts, result.proceed)
            }
        }
    }

    // ── Conflict dialog ───────────────────────────────────────────────────

    /**
     * Shows a scrollable, copyable table of conflicting rows and asks the
     * user whether to cancel or continue inserting them as new records.
     *
     * If the user chooses to continue, [proceed] is called on a background
     * thread so the UI remains responsive.
     */
    private fun showConflictDialog(conflicts: List<ConflictRow>, proceed: () -> ImportResult) {
        val dialog = Dialog<ButtonType>().apply {
            title      = "Possible duplicate rows found"
            headerText = "${conflicts.size} row${if (conflicts.size == 1) "" else "s"} in the " +
                    "spreadsheet may already exist in the database.\n" +
                    "They have no id but match existing records on all data fields.\n\n" +
                    "Cancel to abort, or Continue to insert them as additional records."
        }

        // Copy the application icons from the owner window so the dialog
        // shows the LibreMTD icon rather than the generic JavaFX icon.
        val ownerWindow = root.scene?.window
        if (ownerWindow is Stage) {
            dialog.dialogPane.sceneProperty().addListener { _, _, newScene ->
                (newScene?.window as? Stage)?.icons?.setAll(ownerWindow.icons)
            }
        }

        // ── Scrollable conflict table ──────────────────────────────────────
        val table = TableView<ConflictRow>().apply {
            isEditable         = false
            columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY
            prefHeight         = 300.0

            columns.addAll(buildList {
                add(TableColumn<ConflictRow, String>("Sheet row").also { col ->
                    col.cellValueFactory = javafx.util.Callback { cdf ->
                        SimpleStringProperty(cdf.value.sheetRowNum.toString())
                    }
                    col.prefWidth = 80.0
                })
                add(TableColumn<ConflictRow, String>("Existing DB id").also { col ->
                    col.cellValueFactory = javafx.util.Callback { cdf ->
                        SimpleStringProperty(cdf.value.existingDbId.toString())
                    }
                    col.prefWidth = 100.0
                })
                if (conflicts.any { it.propertyDisplay != null }) {
                    add(TableColumn<ConflictRow, String>("Property").also { col ->
                        col.cellValueFactory = javafx.util.Callback { cdf ->
                            SimpleStringProperty(cdf.value.propertyDisplay ?: "")
                        }
                        col.prefWidth = 160.0
                    })
                }
                add(TableColumn<ConflictRow, String>("Category").also { col ->
                    col.cellValueFactory = javafx.util.Callback { cdf ->
                        SimpleStringProperty(cdf.value.category)
                    }
                    col.prefWidth = 140.0
                })
                add(TableColumn<ConflictRow, String>("Amount").also { col ->
                    col.cellValueFactory = javafx.util.Callback { cdf ->
                        SimpleStringProperty("%.2f".format(cdf.value.amount))
                    }
                    col.prefWidth = 90.0
                })
                add(TableColumn<ConflictRow, String>("Description").also { col ->
                    col.cellValueFactory = javafx.util.Callback { cdf ->
                        SimpleStringProperty(cdf.value.description)
                    }
                })
                add(TableColumn<ConflictRow, String>("Date").also { col ->
                    col.cellValueFactory = javafx.util.Callback { cdf ->
                        SimpleStringProperty(cdf.value.transactionDate)
                    }
                    col.prefWidth = 100.0
                })
            })

            items.setAll(conflicts)
        }

        // Ctrl+C copies selected rows as tab-separated text.
        table.setOnKeyPressed { e ->
            if (e.isControlDown && e.code == javafx.scene.input.KeyCode.C) {
                val selected = table.selectionModel.selectedItems
                if (selected.isNotEmpty()) {
                    val text = selected.joinToString("\n") { r ->
                        listOfNotNull(
                            r.sheetRowNum.toString(),
                            r.existingDbId.toString(),
                            r.propertyDisplay,
                            r.category,
                            "%.2f".format(r.amount),
                            r.description,
                            r.transactionDate,
                        ).joinToString("\t")
                    }
                    javafx.scene.input.Clipboard.getSystemClipboard().setContent(
                        javafx.scene.input.ClipboardContent().also { it.putString(text) }
                    )
                }
            }
        }

        val cancelButton   = ButtonType("Cancel",   ButtonBar.ButtonData.CANCEL_CLOSE)
        val continueButton = ButtonType("Continue", ButtonBar.ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.addAll(cancelButton, continueButton)
        dialog.dialogPane.content = VBox(8.0,
            wrappingLabel("Ctrl+C copies selected rows to clipboard.").apply {
                style = "-fx-text-fill: #555555; -fx-font-size: 11px;"
            },
            table,
        ).also { it.padding = Insets(8.0) }

        dialog.dialogPane.prefWidth  = 800.0
        dialog.dialogPane.prefHeight = 420.0

        val chosen = dialog.showAndWait().orElse(cancelButton)
        if (chosen != continueButton) {
            setStatus("Import cancelled.", error = false)
            return
        }

        // User confirmed — run the import on a background thread.
        importButton.isDisable = true
        setStatus("Importing…")
        scope.launch(Dispatchers.IO) {
            val result = try {
                proceed()
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
