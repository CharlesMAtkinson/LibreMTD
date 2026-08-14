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
import org.charlesatkinson.libremtd.database.IncomePropertyUkEntry
import org.charlesatkinson.libremtd.database.IncomePropertyUkRepository
import org.charlesatkinson.libremtd.database.Property
import org.charlesatkinson.libremtd.database.PropertyType
import org.charlesatkinson.libremtd.ui.components.Dialogs
import org.charlesatkinson.libremtd.ui.components.PeriodSelector
import org.charlesatkinson.libremtd.ui.components.PropertySelector
import org.charlesatkinson.libremtd.ui.components.wrappingLabel
import java.time.LocalDate
import java.time.format.DateTimeParseException

class IncomePropertyUkPane(
    private val scope: CoroutineScope,
    private val userId: Int,
    private val onStatusChange: (String) -> Unit,
) {

    val root: VBox

    private val entries = FXCollections.observableArrayList<IncomePropertyUkEntry>()
    private val totalLabel = wrappingLabel("£0.00").apply {
        styleClass.add("total-value-label")
    }

    private var currentPeriodId: Int?   = null
    private var currentProperty: Property? = null

    private val propertySelector = PropertySelector(userId, PropertyType.UK) { property ->
        currentProperty = property
        reloadIfReady()
    }

    private val periodSelector = PeriodSelector(userId = userId)  { period ->
        currentPeriodId = period?.id
        reloadIfReady()
    }

    private val categoryPicker = ComboBox<IncomeCategory>()
    private val amountField    = TextField()
    private val descField      = TextField()
    private val dateField      = TextField()

    init {
        root = buildUI()
    }

    private fun buildUI(): VBox {
        return VBox(16.0).apply {
            padding = Insets(4.0)
            children.addAll(
                wrappingLabel("Income (property, UK)").apply {
                    style = "-fx-font-size: 22px; -fx-font-weight: bold;"
                },
                wrappingLabel("Record income received for the selected property and quarter.").apply {
                    styleClass.add("hint-label")
                },
                propertySelector.root,
                periodSelector.root,
                buildEntryForm(),
                buildEntriesTable(),
                buildTotalBar(),
            )
        }
    }

    private fun reloadIfReady() {
        val periodId   = currentPeriodId
        val property   = currentProperty
        if (periodId == null || property == null) {
            entries.clear()
            onStatusChange("No period or property selected")
            return
        }
        loadEntries(periodId, property.id)
        onStatusChange("Loaded income for ${property.address}")
    }

    private fun loadEntries(periodId: Int, propertyId: Int) {
        scope.launch(Dispatchers.IO) {
            val loaded = IncomePropertyUkRepository.currentForPeriodAndProperty(periodId, propertyId)
            kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                entries.setAll(loaded)
                refreshTotal()
            }
        }
    }

    private fun buildEntryForm(): VBox {
        categoryPicker.apply {
            items.setAll(*IncomeCategory.values())
            promptText = "Category"
            prefWidth  = 220.0
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

        val addBtn = Button("Add").apply {
            styleClass.add("primary-action-button")
            setOnAction { handleAdd() }
        }

        return VBox(8.0).apply {
            padding = Insets(12.0, 16.0, 12.0, 16.0)
            styleClass.add("content-card")
            style   = "-fx-border-radius: 8; -fx-background-radius: 8;"
            children.addAll(
                wrappingLabel("New entry").apply { style = "-fx-font-weight: bold;" },
                Separator(),
                HBox(10.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(categoryPicker, amountField, descField, dateField, addBtn)
                },
            )
        }
    }

    private fun handleAdd() {
        val periodId = currentPeriodId
        val property = currentProperty
        if (periodId == null || property == null) {
            Dialogs.showError("Please select a property and period first.")
            return
        }

        val errors     = mutableListOf<String>()
        val category   = categoryPicker.value
        val amountText = amountField.text.trim()
        val amount     = amountText.toDoubleOrNull()
        val desc       = descField.text.trim()
        val dateText   = dateField.text.trim()

        if (category == null)            errors += "Please select a category."
        if (amountText.isBlank())        errors += "Please enter an amount."
        else if (amount == null)         errors += "Amount must be a number (e.g. 1250.00)."
        else if (amount <= 0)            errors += "Amount must be greater than zero."
        if (desc.isBlank())              errors += "Please enter a description."
        if (dateText.isBlank())          errors += "Please enter a transaction date."
        else if (!isValidDate(dateText)) errors += "Date must be in format YYYY-MM-DD (e.g. 2025-07-15)."

        if (errors.isNotEmpty()) {
            Dialogs.showError(errors.joinToString("\n"), title = "Validation Error")
            return
        }

        scope.launch(Dispatchers.IO) {
            val entry = IncomePropertyUkRepository.recordPropertyIncome(
                periodId        = periodId,
                userId          = userId,
                propertyId      = property.id,
                category        = category!!.dbKey,
                amount          = amount!!,
                description     = desc,
                transactionDate = dateText,
            )
            kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                entries.add(entry)
                refreshTotal()
                clearForm()
                onStatusChange("Income entry added ✓")
            }
        }
    }

    private fun buildEntriesTable(): VBox {
        val table = TableView<IncomePropertyUkEntry>(entries).apply {
            prefHeight  = 260.0
            placeholder = wrappingLabel("No income entries for this period")
            columns.addAll(
                TableColumn<IncomePropertyUkEntry, String>("Date").apply {
                    prefWidth = 110.0
                    setCellValueFactory { SimpleStringProperty(it.value.transactionDate) }
                },
                TableColumn<IncomePropertyUkEntry, String>("Category").apply {
                    prefWidth = 190.0
                    setCellValueFactory {
                        SimpleStringProperty(
                            IncomeCategory.entries
                                .firstOrNull { c -> c.dbKey == it.value.category }?.label
                                ?: it.value.category
                        )
                    }
                },
                TableColumn<IncomePropertyUkEntry, String>("Description").apply {
                    prefWidth = 200.0
                    setCellValueFactory { SimpleStringProperty(it.value.description) }
                },
                TableColumn<IncomePropertyUkEntry, String>("Amount").apply {
                    prefWidth = 100.0
                    style     = "-fx-alignment: CENTER-RIGHT;"
                    setCellValueFactory { SimpleStringProperty("£%.2f".format(it.value.amount)) }
                },
            )
        }

        val deleteBtn = Button("Delete selected").apply {
            styleClass.add("primary-action-button")
            setOnAction {
                val selected = table.selectionModel.selectedItem
                if (selected == null) {
                    Dialogs.showError("Please select an entry to delete.")
                    return@setOnAction
                }
                val confirmed = Dialogs.showConfirmation(
                    message    = "Delete this property income entry?",
                    title      = "Delete entry",
                    headerText = "Are you sure?",
                )
                if (!confirmed) return@setOnAction
                scope.launch(Dispatchers.IO) {
                    IncomePropertyUkRepository.delete(selected.id)
                    kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                        entries.remove(selected)
                        refreshTotal()
                        onStatusChange("Entry deleted")
                    }
                }
            }
        }

        return VBox(8.0).apply {
            padding = Insets(12.0, 16.0, 12.0, 16.0)
            styleClass.add("content-card")
            style   = "-fx-border-radius: 8; -fx-background-radius: 8;"
            children.addAll(
                wrappingLabel("Entries this period").apply { style = "-fx-font-weight: bold;" },
                Separator(),
                table,
                deleteBtn,
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
                wrappingLabel("Period total:").apply { style = "-fx-font-weight: bold;" },
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

    private fun categoryCell() = object : ListCell<IncomeCategory>() {
        override fun updateItem(item: IncomeCategory?, empty: Boolean) {
            super.updateItem(item, empty)
            text = if (empty || item == null) null else item.label
        }
    }
}
