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
import javafx.collections.FXCollections
import javafx.beans.property.SimpleStringProperty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import org.charlesatkinson.libremtd.database.IncomeSavingsEntry
import org.charlesatkinson.libremtd.database.IncomeSavingsRepository
import org.charlesatkinson.libremtd.database.SubmissionRepository
import org.charlesatkinson.libremtd.database.components.FinalDeclarationLockedException
import org.charlesatkinson.libremtd.database.taxYearForDate
import org.charlesatkinson.libremtd.ui.components.Dialogs
import org.charlesatkinson.libremtd.ui.components.FinalDeclarationLock
import org.charlesatkinson.libremtd.ui.components.TaxYearSelector
import org.charlesatkinson.libremtd.ui.components.hintLabel
import org.charlesatkinson.libremtd.ui.components.wrappingLabel
import org.charlesatkinson.libremtd.ui.components.wrongTaxYearMessage
import java.time.LocalDate
import java.time.format.DateTimeParseException

class SavingsIncomePane(
    private val scope: CoroutineScope,
    private val userId: Int,
    private val onStatusChange: (String) -> Unit,
) {

    val root: VBox

    private val entries    = FXCollections.observableArrayList<IncomeSavingsEntry>()
    private val totalLabel = wrappingLabel("£0.00").apply {
        styleClass.add("total-value-label")
    }

    private lateinit var currentTaxYear: String

    // Declared before taxYearSelector below: TaxYearSelector may invoke
    // its callback synchronously during construction, and that callback
    // (via loadEntries -> applyLock) uses this field.
    private val finalDeclarationLock = FinalDeclarationLock()

    // Bumped on every reload request; a coroutine only applies its result
    // if this hasn't moved on since it captured its own value. Guards
    // against two overlapping loadEntries() calls resolving out of order
    // and the stale one overwriting the current pane state, including the
    // Final Declaration lock.
    private var loadGeneration = 0

    // Non-null while the form is populated with an existing entry rather
    // than a blank one — see enterEditMode()/exitEditMode(). Note that a
    // saved edit produces a NEW row id (edit() supersedes the old row and
    // inserts a fresh one — see IncomeSavingsRepository.edit), so this is
    // only ever used to identify which row to supersede, never to look up
    // the resulting entry afterwards.
    private var editingEntryId: Int? = null

    private val taxYearSelector = TaxYearSelector(userId = userId) { year ->
        currentTaxYear = year
        loadEntries()
    }

    private val categoryPicker = ComboBox<SavingsCategory>()
    private val amountField    = TextField()
    private val descField      = TextField()
    private val dateField      = TextField()
    private var addBtn: Button?       = null
    private var cancelEditBtn: Button? = null
    private var editBtn: Button?      = null
    private var deleteBtn: Button?    = null

    private val entryFormHeading   = wrappingLabel("New entry").apply { style = "-fx-font-weight: bold;" }
    private val entriesHeading     = wrappingLabel("").apply { style = "-fx-font-weight: bold;" }
    private val entriesPlaceholder = wrappingLabel("")

    init {
        root = buildUI()
    }

    private fun buildUI(): VBox {
        return VBox(16.0).apply {
            padding = Insets(4.0)
            children.addAll(
                wrappingLabel("Income (savings)").apply {
                    style = "-fx-font-size: 22px; -fx-font-weight: bold;"
                },
                hintLabel("Record savings interest received. The tax year is derived from the transaction date."),
                taxYearSelector.root,
                finalDeclarationLock.banner,
                buildEntryForm(),
                buildEntriesTable(),
                buildTotalBar(),
            )
        }
    }

    private fun loadEntries() {
        val generation = ++loadGeneration
        scope.launch(Dispatchers.IO) {
            val loaded = IncomeSavingsRepository.currentForTaxYear(userId, currentTaxYear)
            val locked = SubmissionRepository.isFinalDeclared(userId, currentTaxYear)
            kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                if (generation != loadGeneration) return@withContext
                entries.setAll(loaded)
                refreshTotal()
                entriesHeading.text     = "Entries in $currentTaxYear"
                entriesPlaceholder.text = "No savings entries for $currentTaxYear"
                applyLock(isFinalDeclared = locked, taxYear = currentTaxYear)
                onStatusChange("Loaded savings income for $currentTaxYear")
            }
        }
    }

    private fun applyLock(isFinalDeclared: Boolean, taxYear: String) {
        val controls = listOfNotNull(categoryPicker, amountField, descField, dateField, addBtn, editBtn, deleteBtn)
        finalDeclarationLock.update(isFinalDeclared, taxYear, *controls.toTypedArray())
    }

    private fun buildEntryForm(): VBox {
        categoryPicker.apply {
            items.setAll(*SavingsCategory.values())
            promptText = "Category"
            prefWidth  = 260.0
            buttonCell = categoryCell()
            setCellFactory { categoryCell() }
        }

        amountField.apply {
            promptText = "Amount (£)"
            prefWidth  = 120.0
        }

        descField.apply {
            promptText = "Description"
            prefWidth  = 220.0
        }

        dateField.apply {
            promptText = "Date (YYYY-MM-DD)"
            prefWidth  = 170.0
        }

        val newAddBtn = Button("Add").apply {
            styleClass.add("primary-action-button")
            setOnAction { if (editingEntryId != null) handleSaveEdit() else handleAdd() }
        }
        addBtn = newAddBtn

        val newCancelEditBtn = Button("Cancel edit").apply {
            styleClass.add("secondary-action-button")
            isVisible = false
            isManaged = false
            setOnAction { exitEditMode() }
        }
        cancelEditBtn = newCancelEditBtn

        return VBox(8.0).apply {
            padding = Insets(12.0, 16.0, 12.0, 16.0)
            styleClass.add("content-card")
            style   = "-fx-border-radius: 8; -fx-background-radius: 8;"
            children.addAll(
                entryFormHeading,
                Separator(),
                HBox(10.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(categoryPicker, amountField, descField, dateField, newAddBtn, newCancelEditBtn)
                },
            )
        }
    }

    private fun handleAdd() {
        val errors     = mutableListOf<String>()
        val category   = categoryPicker.value
        val amountText = amountField.text.trim()
        val amount     = amountText.toDoubleOrNull()
        val desc       = descField.text.trim()
        val dateText   = dateField.text.trim()

        if (category == null)            errors += "Please select a category."
        if (amountText.isBlank())        errors += "Please enter an amount."
        else if (amount == null)         errors += "Amount must be a number (e.g. 125.50)."
        else if (amount <= 0)            errors += "Amount must be greater than zero."
        if (desc.isBlank())              errors += "Please enter a description."
        if (dateText.isBlank())          errors += "Please enter a transaction date."
        else if (!isValidDate(dateText)) errors += "Date must be in format YYYY-MM-DD (e.g. 2025-07-15)."

        if (errors.isNotEmpty()) {
            Dialogs.showError(errors.joinToString("\n"), title = "Validation Error")
            return
        }

        val derivedTaxYear = taxYearForDate(dateText)

        if (derivedTaxYear != currentTaxYear) {
            Dialogs.showError(
                wrongTaxYearMessage(dateText, derivedTaxYear, currentTaxYear),
                title = "Wrong tax year"
            )
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val entry = IncomeSavingsRepository.recordSavingsIncome(
                    userId          = userId,
                    taxYear         = derivedTaxYear,
                    category        = category!!.dbKey,
                    amount          = amount!!,
                    description     = desc,
                    transactionDate = dateText,
                )
                kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                    entries.add(entry)
                    refreshTotal()
                    clearForm()
                    onStatusChange("Savings entry added ✓")
                }
            } catch (e: FinalDeclarationLockedException) {
                kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                    Dialogs.showError(e.message ?: "This tax year can no longer be amended.", title = "Tax year locked")
                    loadEntries()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Edit — reuses the New Entry form. Selecting a row and clicking
    // "Edit selected" populates the form and turns the Add button into
    // "Save changes"; Cancel edit returns to add mode without saving.
    // -------------------------------------------------------------------------

    private fun handleEditSelected(table: TableView<IncomeSavingsEntry>) {
        val selected = table.selectionModel.selectedItem
        if (selected == null) {
            Dialogs.showError("Please select an entry to edit.")
            return
        }
        enterEditMode(selected)
    }

    private fun enterEditMode(entry: IncomeSavingsEntry) {
        editingEntryId = entry.id

        categoryPicker.value = SavingsCategory.entries.firstOrNull { it.dbKey == entry.category }
        amountField.text     = "%.2f".format(entry.amount)
        descField.text        = entry.description
        dateField.text        = entry.transactionDate

        entryFormHeading.text = "Edit entry"
        addBtn?.text          = "Save changes"
        cancelEditBtn?.isVisible = true
        cancelEditBtn?.isManaged = true

        // Switching tax year mid-edit would leave the form showing an
        // entry that no longer belongs to the year being viewed — simplest
        // to just disable the selector until the edit is saved or cancelled.
        taxYearSelector.root.isDisable = true

        // Prevent starting a second edit, or deleting the row currently
        // being edited, while the form is mid-edit.
        editBtn?.isDisable   = true
        deleteBtn?.isDisable = true
    }

    private fun exitEditMode() {
        editingEntryId = null
        entryFormHeading.text = "New entry"
        addBtn?.text          = "Add"
        cancelEditBtn?.isVisible = false
        cancelEditBtn?.isManaged = false
        taxYearSelector.root.isDisable = false
        editBtn?.isDisable   = false
        deleteBtn?.isDisable = false
        clearForm()
    }

    private fun handleSaveEdit() {
        val existingId = editingEntryId ?: return

        val errors     = mutableListOf<String>()
        val category   = categoryPicker.value
        val amountText = amountField.text.trim()
        val amount     = amountText.toDoubleOrNull()
        val desc       = descField.text.trim()
        val dateText   = dateField.text.trim()

        if (category == null)            errors += "Please select a category."
        if (amountText.isBlank())        errors += "Please enter an amount."
        else if (amount == null)         errors += "Amount must be a number (e.g. 125.50)."
        else if (amount <= 0)            errors += "Amount must be greater than zero."
        if (desc.isBlank())              errors += "Please enter a description."
        if (dateText.isBlank())          errors += "Please enter a transaction date."
        else if (!isValidDate(dateText)) errors += "Date must be in format YYYY-MM-DD (e.g. 2025-07-15)."

        if (errors.isNotEmpty()) {
            Dialogs.showError(errors.joinToString("\n"), title = "Validation Error")
            return
        }

        val derivedTaxYear = taxYearForDate(dateText)

        if (derivedTaxYear != currentTaxYear) {
            Dialogs.showError(
                wrongTaxYearMessage(dateText, derivedTaxYear, currentTaxYear, editing = true),
                title = "Wrong tax year"
            )
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val edited = IncomeSavingsRepository.edit(
                    existingId      = existingId,
                    userId          = userId,
                    taxYear         = derivedTaxYear,
                    category        = category!!.dbKey,
                    amount          = amount!!,
                    description     = desc,
                    transactionDate = dateText,
                )
                kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                    // edit() supersedes the old row and inserts a new one
                    // with a new id, so the old row is replaced by index
                    // rather than updated by matching id.
                    val idx = entries.indexOfFirst { it.id == existingId }
                    if (idx >= 0) entries[idx] = edited else entries.add(edited)
                    refreshTotal()
                    exitEditMode()
                    onStatusChange("Savings entry updated ✓")
                }
            } catch (e: FinalDeclarationLockedException) {
                kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                    Dialogs.showError(e.message ?: "This tax year can no longer be amended.", title = "Tax year locked")
                    exitEditMode()
                    loadEntries()
                }
            }
        }
    }

    private fun buildEntriesTable(): VBox {
        val table = TableView<IncomeSavingsEntry>(entries).apply {
            prefHeight  = 260.0
            placeholder = entriesPlaceholder
            columns.addAll(
                TableColumn<IncomeSavingsEntry, String>("Date").apply {
                    prefWidth = 110.0
                    setCellValueFactory { SimpleStringProperty(it.value.transactionDate) }
                },
                TableColumn<IncomeSavingsEntry, String>("Category").apply {
                    prefWidth = 240.0
                    setCellValueFactory {
                        SimpleStringProperty(
                            SavingsCategory.entries
                                .firstOrNull { c -> c.dbKey == it.value.category }?.label
                                ?: it.value.category
                        )
                    }
                },
                TableColumn<IncomeSavingsEntry, String>("Description").apply {
                    prefWidth = 200.0
                    setCellValueFactory { SimpleStringProperty(it.value.description) }
                },
                TableColumn<IncomeSavingsEntry, String>("Amount").apply {
                    prefWidth = 100.0
                    style     = "-fx-alignment: CENTER-RIGHT;"
                    setCellValueFactory { SimpleStringProperty("£%.2f".format(it.value.amount)) }
                },
            )
        }

        val newEditBtn = Button("Edit selected").apply {
            styleClass.add("primary-action-button")
            setOnAction { handleEditSelected(table) }
        }
        editBtn = newEditBtn

        val newDeleteBtn = Button("Delete selected").apply {
            styleClass.add("primary-action-button")
            setOnAction {
                val selected = table.selectionModel.selectedItem
                if (selected == null) {
                    Dialogs.showError("Please select an entry to delete.")
                    return@setOnAction
                }
                val confirmed = Dialogs.showConfirmation(
                    message    = "Delete this savings income entry?",
                    title      = "Delete entry",
                    headerText = "Are you sure?",
                )
                if (!confirmed) return@setOnAction
                scope.launch(Dispatchers.IO) {
                    try {
                        IncomeSavingsRepository.delete(selected.id)
                        kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                            entries.remove(selected)
                            refreshTotal()
                            onStatusChange("Entry deleted")
                        }
                    } catch (e: FinalDeclarationLockedException) {
                        kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                            Dialogs.showError(e.message ?: "This tax year can no longer be amended.", title = "Tax year locked")
                            loadEntries()
                        }
                    }
                }
            }
        }
        deleteBtn = newDeleteBtn

        return VBox(8.0).apply {
            padding = Insets(12.0, 16.0, 12.0, 16.0)
            styleClass.add("content-card")
            style   = "-fx-border-radius: 8; -fx-background-radius: 8;"
            children.addAll(
                entriesHeading,
                Separator(),
                table,
                HBox(10.0, newEditBtn, newDeleteBtn),
            )
        }
    }

    private fun buildTotalBar(): HBox {
        return HBox(24.0).apply {
            padding   = Insets(12.0, 16.0, 12.0, 16.0)
            styleClass.add("total-bar")
            style     = "-fx-border-radius: 6; -fx-background-radius: 6;"
            alignment = Pos.CENTER_LEFT
            children.addAll(
                wrappingLabel("Year total:").apply { style = "-fx-font-weight: bold;" },
                totalLabel,
            )
        }
    }

    private fun refreshTotal() {
        totalLabel.text = "£%.2f".format(entries.sumOf { it.amount })
    }

    private fun clearForm() {
        categoryPicker.value = null
        amountField.clear()
        descField.clear()
        dateField.clear()
    }

    private fun isValidDate(text: String): Boolean =
        try { LocalDate.parse(text); true } catch (_: DateTimeParseException) { false }

    private fun categoryCell() = object : ListCell<SavingsCategory>() {
        override fun updateItem(item: SavingsCategory?, empty: Boolean) {
            super.updateItem(item, empty)
            text = if (empty || item == null) null else item.label
        }
    }
}
