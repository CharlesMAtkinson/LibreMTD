/*
 *
 *  * Copyright (C) 2026 Charles Michael Atkinson
 *  *
 *  * This program is free software: you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation, either version 3 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package org.charlesatkinson.libremtd.ui

import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.charlesatkinson.libremtd.database.ExpensePropertyForeignEntry
import org.charlesatkinson.libremtd.database.ExpensePropertyForeignRepository
import org.charlesatkinson.libremtd.database.PeriodRepository
import org.charlesatkinson.libremtd.database.Property
import org.charlesatkinson.libremtd.database.PropertyType
import org.charlesatkinson.libremtd.database.SubmissionRepository
import org.charlesatkinson.libremtd.database.components.FinalDeclarationLockedException
import org.charlesatkinson.libremtd.database.taxYearForDate
import org.charlesatkinson.libremtd.ui.components.Dialogs
import org.charlesatkinson.libremtd.ui.components.FinalDeclarationLock
import org.charlesatkinson.libremtd.ui.components.PropertySelector
import org.charlesatkinson.libremtd.ui.components.TaxYearSelector
import org.charlesatkinson.libremtd.ui.components.hintLabel
import org.charlesatkinson.libremtd.ui.components.wrappingLabel
import org.charlesatkinson.libremtd.ui.components.wrongTaxYearMessage
import java.time.LocalDate
import java.time.format.DateTimeParseException

class ExpensesPropertyForeignPane(
    private val scope: CoroutineScope,
    private val userId: Int,
    private val onStatusChange: (String) -> Unit,
) {

    val root: VBox

    private val entries    = FXCollections.observableArrayList<ExpensePropertyForeignEntry>()
    private val totalLabel = wrappingLabel("£0.00").apply {
        styleClass.add("expense-total-value-label")
    }

    private var currentProperty: Property? = null
    private var currentTaxYear: String?    = null

    // Everything the selectors' callbacks can touch is declared BEFORE the
    // selectors: they may call back during their own construction.
    private val finalDeclarationLock = FinalDeclarationLock()

    // Bumped on every reload request; a coroutine only applies its result
    // if this has not moved on since it captured its own value.
    private var loadGeneration = 0

    // Non-null while the form is populated with an existing entry. A saved
    // edit produces a NEW row id (edit() supersedes the old row and
    // inserts a fresh one), so this only identifies which row to supersede.
    private var editingEntryId: Int? = null

    private val categoryPicker = ComboBox<ForeignExpenseCategory>()
    private val amountField    = TextField()
    private val descField      = TextField()
    private val dateField      = TextField()
    private var addBtn: Button?        = null
    private var cancelEditBtn: Button? = null
    private var editBtn: Button?       = null
    private var deleteBtn: Button?     = null

    private val entryFormHeading   = wrappingLabel("New entry").apply { style = "-fx-font-weight: bold;" }
    private val entriesHeading     = wrappingLabel("").apply { style = "-fx-font-weight: bold;" }
    private val entriesPlaceholder = wrappingLabel("")

    private val propertySelector = PropertySelector(userId, PropertyType.FOREIGN) { property ->
        currentProperty = property
        reloadIfReady()
    }

    private val taxYearSelector = TaxYearSelector(userId = userId) { year ->
        currentTaxYear = year
        reloadIfReady()
    }

    init {
        root = buildUI()
    }

    private fun buildUI(): VBox {
        return VBox(16.0).apply {
            padding = Insets(4.0)
            children.addAll(
                wrappingLabel("Expenses (property, foreign)").apply {
                    style = "-fx-font-size: 22px; -fx-font-weight: bold;"
                },
                hintLabel(
                    "Record allowable expenses for the selected foreign property. " +
                            "The tax year is derived from the transaction date."
                ),
                propertySelector.root,
                taxYearSelector.root,
                finalDeclarationLock.banner,
                buildEntryForm(),
                buildEntriesTable(),
                buildTotalBar(),
            )
        }
    }

    private fun reloadIfReady() {
        loadGeneration++
        val property = currentProperty
        val taxYear  = currentTaxYear
        if (property == null || taxYear == null) {
            entries.clear()
            refreshTotal()
            applyLock(isFinalDeclared = false, taxYear = "")
            onStatusChange("No property or tax year selected")
            return
        }
        loadEntries(property.id, taxYear)
        onStatusChange("Loaded expenses for ${property.address}")
    }

    private fun loadEntries(propertyId: Int, taxYear: String) {
        val generation = loadGeneration
        scope.launch(Dispatchers.IO) {
            val loaded = ExpensePropertyForeignRepository.currentForPropertyAndYear(propertyId, taxYear)
            val locked = SubmissionRepository.isFinalDeclared(userId, taxYear)
            withContext(Dispatchers.JavaFx) {
                if (generation != loadGeneration) return@withContext
                entries.setAll(loaded)
                refreshTotal()
                entriesHeading.text     = "Entries in $taxYear"
                entriesPlaceholder.text = "No expense entries for $taxYear"
                applyLock(isFinalDeclared = locked, taxYear = taxYear)
            }
        }
    }

    private fun applyLock(isFinalDeclared: Boolean, taxYear: String) {
        val controls = listOfNotNull(categoryPicker, amountField, descField, dateField, addBtn, editBtn, deleteBtn)
        finalDeclarationLock.update(isFinalDeclared, taxYear, *controls.toTypedArray())
    }

    // -------------------------------------------------------------------------
    // Entry form, shared between "New entry" and "Edit entry". Field order
    // matches the table columns below: Date, Category, Description, Amount.
    // -------------------------------------------------------------------------

    private fun buildEntryForm(): VBox {
        categoryPicker.apply {
            items.setAll(*ForeignExpenseCategory.values())
            promptText = "Category"
            prefWidth  = 300.0
            buttonCell = categoryCell()
            setCellFactory { categoryCell() }
        }

        amountField.apply {
            promptText = "Amount (£)"
            prefWidth  = 120.0
        }

        descField.apply {
            promptText = "Description"
            prefWidth  = 200.0
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
                    children.addAll(dateField, categoryPicker, descField, amountField, newAddBtn, newCancelEditBtn)
                },
            )
        }
    }

    /** Validates the form. Returns the error text, or null if valid. */
    private fun validationErrors(): String? {
        val errors     = mutableListOf<String>()
        val amountText = amountField.text.trim()
        val amount     = amountText.toDoubleOrNull()
        val dateText   = dateField.text.trim()

        if (categoryPicker.value == null) errors += "Please select a category."
        if (amountText.isBlank())         errors += "Please enter an amount."
        else if (amount == null)          errors += "Amount must be a number (e.g. 250.00)."
        else if (amount <= 0)             errors += "Amount must be greater than zero."
        if (descField.text.trim().isBlank()) errors += "Please enter a description."
        if (dateText.isBlank())           errors += "Please enter a transaction date."
        else if (!isValidDate(dateText))  errors += "Date must be in format YYYY-MM-DD (e.g. 2026-07-15)."

        return if (errors.isEmpty()) null else errors.joinToString("\n")
    }

    private fun handleAdd() {
        val property = currentProperty
        val taxYear  = currentTaxYear
        if (property == null || taxYear == null) {
            Dialogs.showError("Please select a property first.")
            return
        }

        validationErrors()?.let { Dialogs.showError(it, title = "Validation Error"); return }

        val category = categoryPicker.value!!
        val amount   = amountField.text.trim().toDouble()
        val desc     = descField.text.trim()
        val dateText = dateField.text.trim()

        val derivedTaxYear = taxYearForDate(dateText)
        if (derivedTaxYear != taxYear) {
            Dialogs.showError(wrongTaxYearMessage(dateText, derivedTaxYear, taxYear), title = "Wrong tax year")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val periodId = PeriodRepository.getOrCreateStandard(dateText).id
                val entry = ExpensePropertyForeignRepository.recordForeignPropertyExpense(
                    periodId        = periodId,
                    userId          = userId,
                    propertyId      = property.id,
                    category        = category.dbKey,
                    amount          = amount,
                    description     = desc,
                    transactionDate = dateText,
                )
                withContext(Dispatchers.JavaFx) {
                    entries.add(entry)
                    refreshTotal()
                    clearForm()
                    onStatusChange("Expense entry added ✓")
                }
            } catch (e: FinalDeclarationLockedException) {
                withContext(Dispatchers.JavaFx) {
                    Dialogs.showError(e.message ?: "This tax year can no longer be amended.", title = "Tax year locked")
                    reloadIfReady()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Edit: reuses the New Entry form. "Edit selected" populates the form and
    // turns Add into "Save changes"; "Cancel edit" returns to add mode.
    // -------------------------------------------------------------------------

    private fun handleEditSelected(table: TableView<ExpensePropertyForeignEntry>) {
        val selected = table.selectionModel.selectedItem
        if (selected == null) {
            Dialogs.showError("Please select an entry to edit.")
            return
        }
        enterEditMode(selected)
    }

    private fun enterEditMode(entry: ExpensePropertyForeignEntry) {
        editingEntryId = entry.id

        categoryPicker.value = ForeignExpenseCategory.entries.firstOrNull { it.dbKey == entry.category }
        amountField.text     = "%.2f".format(entry.amount)
        descField.text       = entry.description
        dateField.text       = entry.transactionDate

        entryFormHeading.text    = "Edit entry"
        addBtn?.text             = "Save changes"
        cancelEditBtn?.isVisible = true
        cancelEditBtn?.isManaged = true

        propertySelector.root.isDisable = true
        taxYearSelector.root.isDisable  = true

        editBtn?.isDisable   = true
        deleteBtn?.isDisable = true
    }

    private fun exitEditMode() {
        editingEntryId = null
        entryFormHeading.text    = "New entry"
        addBtn?.text             = "Add"
        cancelEditBtn?.isVisible = false
        cancelEditBtn?.isManaged = false
        propertySelector.root.isDisable = false
        taxYearSelector.root.isDisable  = false
        editBtn?.isDisable   = false
        deleteBtn?.isDisable = false
        clearForm()
    }

    private fun handleSaveEdit() {
        val existingId = editingEntryId ?: return
        val property   = currentProperty
        val taxYear    = currentTaxYear
        if (property == null || taxYear == null) {
            Dialogs.showError("Please select a property first.")
            return
        }

        validationErrors()?.let { Dialogs.showError(it, title = "Validation Error"); return }

        val category = categoryPicker.value!!
        val amount   = amountField.text.trim().toDouble()
        val desc     = descField.text.trim()
        val dateText = dateField.text.trim()

        val derivedTaxYear = taxYearForDate(dateText)
        if (derivedTaxYear != taxYear) {
            Dialogs.showError(
                wrongTaxYearMessage(dateText, derivedTaxYear, taxYear, editing = true),
                title = "Wrong tax year",
            )
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val periodId = PeriodRepository.getOrCreateStandard(dateText).id
                val edited = ExpensePropertyForeignRepository.edit(
                    existingId      = existingId,
                    periodId        = periodId,
                    userId          = userId,
                    propertyId      = property.id,
                    category        = category.dbKey,
                    amount          = amount,
                    description     = desc,
                    transactionDate = dateText,
                )
                withContext(Dispatchers.JavaFx) {
                    val idx = entries.indexOfFirst { it.id == existingId }
                    if (idx >= 0) entries[idx] = edited else entries.add(edited)
                    refreshTotal()
                    exitEditMode()
                    onStatusChange("Expense entry updated ✓")
                }
            } catch (e: FinalDeclarationLockedException) {
                withContext(Dispatchers.JavaFx) {
                    Dialogs.showError(e.message ?: "This tax year can no longer be amended.", title = "Tax year locked")
                    exitEditMode()
                    reloadIfReady()
                }
            }
        }
    }

    private fun buildEntriesTable(): VBox {
        val table = TableView<ExpensePropertyForeignEntry>(entries).apply {
            prefHeight  = 260.0
            placeholder = entriesPlaceholder
            columns.addAll(
                TableColumn<ExpensePropertyForeignEntry, String>("Date").apply {
                    prefWidth = 110.0
                    setCellValueFactory { SimpleStringProperty(it.value.transactionDate) }
                },
                TableColumn<ExpensePropertyForeignEntry, String>("Category").apply {
                    prefWidth = 240.0
                    setCellValueFactory {
                        SimpleStringProperty(
                            ForeignExpenseCategory.entries
                                .firstOrNull { c -> c.dbKey == it.value.category }?.label
                                ?: it.value.category
                        )
                    }
                },
                TableColumn<ExpensePropertyForeignEntry, String>("Description").apply {
                    prefWidth = 180.0
                    setCellValueFactory { SimpleStringProperty(it.value.description) }
                },
                TableColumn<ExpensePropertyForeignEntry, String>("Amount").apply {
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
                    message    = "Delete this expense entry?",
                    title      = "Delete entry",
                    headerText = "Are you sure?",
                )
                if (!confirmed) return@setOnAction
                scope.launch(Dispatchers.IO) {
                    try {
                        ExpensePropertyForeignRepository.delete(selected.id)
                        withContext(Dispatchers.JavaFx) {
                            entries.remove(selected)
                            refreshTotal()
                            onStatusChange("Entry deleted")
                        }
                    } catch (e: FinalDeclarationLockedException) {
                        withContext(Dispatchers.JavaFx) {
                            Dialogs.showError(e.message ?: "This tax year can no longer be amended.", title = "Tax year locked")
                            reloadIfReady()
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
            styleClass.add("expense-total-bar")
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

    private fun isValidDate(text: String): Boolean {
        return try { LocalDate.parse(text); true } catch (_: DateTimeParseException) { false }
    }

    private fun categoryCell() = object : ListCell<ForeignExpenseCategory>() {
        override fun updateItem(item: ForeignExpenseCategory?, empty: Boolean) {
            super.updateItem(item, empty)
            text = if (empty || item == null) null else item.label
        }
    }
}
