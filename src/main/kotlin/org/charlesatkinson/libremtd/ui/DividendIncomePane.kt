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
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.charlesatkinson.libremtd.database.IncomeDividendEntry
import org.charlesatkinson.libremtd.database.IncomeDividendForeignEntry
import org.charlesatkinson.libremtd.database.IncomeDividendForeignRepository
import org.charlesatkinson.libremtd.database.IncomeDividendRepository
import org.charlesatkinson.libremtd.database.SubmissionRepository
import org.charlesatkinson.libremtd.database.components.FinalDeclarationLockedException
import org.charlesatkinson.libremtd.database.taxYearForDate
import org.charlesatkinson.libremtd.ui.components.Dialogs
import org.charlesatkinson.libremtd.ui.components.FinalDeclarationLock
import org.charlesatkinson.libremtd.ui.components.TaxYearSelector
import org.charlesatkinson.libremtd.ui.components.wrappingLabel
import org.charlesatkinson.libremtd.ui.components.infoPopup
import org.charlesatkinson.libremtd.ui.components.hintLabel
import org.charlesatkinson.libremtd.ui.components.wrongTaxYearMessage
import java.time.LocalDate
import java.time.format.DateTimeParseException

private val logger = KotlinLogging.logger {}

class DividendIncomePane(
    private val scope: CoroutineScope,
    private val userId: Int,
    private val onStatusChange: (String) -> Unit,
) {

    val root: VBox

    // --- Final Declaration gate, shared across all three sections below,
    //     since they are all scoped to the one currentTaxYear ---
    private val finalDeclarationLock = FinalDeclarationLock()

    // Bumped on every reload request; a coroutine only applies its result
    // if this hasn't moved on since it captured its own value. Guards
    // against two overlapping loadAllEntries() calls (e.g. from rapid tax
    // year changes) resolving out of order and the stale one overwriting
    // the current pane state, including the Final Declaration lock.
    private var loadGeneration = 0

    // --- UK dividends from companies and funds ---
    private val ukEntries        = FXCollections.observableArrayList<IncomeDividendEntry>()
    private val ukTotalLabel     = wrappingLabel("£0.00").apply { styleClass.add("total-value-label") }
    private val ukCategoryPicker = ComboBox<DividendCategory>()
    private val ukAmountField    = TextField()
    private val ukDescField      = TextField()
    private val ukDateField      = TextField()
    private var ukAddBtn: Button?       = null
    private var ukCancelEditBtn: Button? = null
    private var ukEditBtn: Button?      = null
    private var ukDeleteBtn: Button?    = null
    private var editingUkEntryId: Int?  = null
    private val ukFormHeading = wrappingLabel("New entry").apply { style = "-fx-font-weight: bold;" }

    // --- UK dividends — special types ---
    private val scalarEntries        = FXCollections.observableArrayList<IncomeDividendEntry>()
    private val scalarTotalLabel     = wrappingLabel("£0.00").apply { styleClass.add("total-value-label") }
    private val scalarCategoryPicker = ComboBox<DividendScalarCategory>()
    private val scalarAmountField    = TextField()
    private val scalarRefField       = TextField()
    private val scalarDateField      = TextField()
    private var scalarAddBtn: Button?       = null
    private var scalarCancelEditBtn: Button? = null
    private var scalarEditBtn: Button?      = null
    private var scalarDeleteBtn: Button?    = null
    private var editingScalarEntryId: Int?  = null
    private val scalarFormHeading = wrappingLabel("New entry").apply { style = "-fx-font-weight: bold;" }

    // --- Foreign dividends ---
    private val foreignEntries           = FXCollections.observableArrayList<IncomeDividendForeignEntry>()
    private val foreignTotalLabel        = wrappingLabel("£0.00").apply { styleClass.add("total-value-label") }
    private val foreignCategoryPicker    = ComboBox<DividendForeignCategory>()
    private val foreignCountryField      = TextField()
    private val foreignAmountBeforeField = TextField()
    private val foreignTaxTakenField     = TextField()
    private val foreignSwtField          = TextField()
    private val foreignFtcrCheck         = CheckBox("Foreign tax credit relief claimed")
    private val foreignTaxableField      = TextField()
    private val foreignDateField         = TextField()
    private var foreignAddBtn: Button?       = null
    private var foreignCancelEditBtn: Button? = null
    private var foreignEditBtn: Button?      = null
    private var foreignDeleteBtn: Button?    = null
    private var editingForeignEntryId: Int?  = null
    private val foreignFormHeading = wrappingLabel("New entry").apply { style = "-fx-font-weight: bold;" }

    private lateinit var currentTaxYear: String

    private val taxYearSelector = TaxYearSelector(userId = userId) { year ->
        currentTaxYear = year
        loadAllEntries()
    }

    init {
        root = buildUI()
    }

    // -------------------------------------------------------------------------
    // Top-level layout
    // -------------------------------------------------------------------------

    private fun buildUI() = VBox(16.0).apply {
        padding = Insets(4.0)
        maxWidth = Double.MAX_VALUE
        children.addAll(
            wrappingLabel("Income (dividends)").apply {
                style = "-fx-font-size: 22px; -fx-font-weight: bold;"
            },
            hintLabel("Record dividend income received during this tax year."),
            taxYearSelector.root,
            finalDeclarationLock.banner,

            sectionHeading("UK dividends from companies and funds"),
            hintLabel(
                "Cash dividends from UK companies (including investment trusts), " +
                        "and dividends from UK unit trusts and OEICs shown on fund platform tax certificates."
            ),
            buildUkEntryForm(),
            buildUkEntriesTable(),
            buildTotalBar(ukTotalLabel),

            sectionHeading("UK dividends — special types"),
            hintLabel(
                "Stock dividends (shares received instead of cash), redeemable shares, " +
                        "bonus issues of securities, and close company loans written off. " +
                        "These arise in unusual circumstances and are reported separately to HMRC. " +
                        "Customer reference is optional; all other fields are required."
            ),
            buildScalarEntryForm(),
            buildScalarEntriesTable(),
            buildTotalBar(scalarTotalLabel),

            sectionHeading("Foreign dividends"),
            hintLabel(
                "Dividends from overseas companies, and dividend income received whilst abroad. " +
                        "Each entry covers one country. Amount before tax, tax taken off, and special " +
                        "withholding tax are optional — enter them if known. Taxable amount is required. " +
                        "Tick 'foreign tax credit relief claimed' only if you are claiming relief for " +
                        "overseas tax already paid — confirm with your tax adviser if unsure."
            ),
            buildForeignEntryForm(),
            buildForeignEntriesTable(),
            buildTotalBar(foreignTotalLabel),
        )
    }

    private fun sectionHeading(text: String) = VBox(4.0).apply {
        children.addAll(
            Separator(),
            wrappingLabel(text).apply { style = "-fx-font-size: 16px; -fx-font-weight: bold;" },
        )
    }

    private fun applyLock(isFinalDeclared: Boolean, taxYear: String) {
        val controls: List<Node> = listOfNotNull(
            ukCategoryPicker, ukAmountField, ukDescField, ukDateField, ukAddBtn, ukEditBtn, ukDeleteBtn,
            scalarCategoryPicker, scalarAmountField, scalarRefField, scalarDateField,
            scalarAddBtn, scalarEditBtn, scalarDeleteBtn,
            foreignCategoryPicker, foreignCountryField, foreignAmountBeforeField, foreignTaxTakenField,
            foreignSwtField, foreignFtcrCheck, foreignTaxableField, foreignDateField,
            foreignAddBtn, foreignEditBtn, foreignDeleteBtn,
        )
        finalDeclarationLock.update(isFinalDeclared, taxYear, *controls.toTypedArray())
    }

    /**
     * Whether the tax year selector should be locked because one of the
     * three sections has an edit in progress. Switching tax year mid-edit
     * would leave the form showing an entry that no longer belongs to the
     * year being viewed, so the selector is locked for the whole pane
     * while any section is being edited, even though the three sections
     * are otherwise independent.
     */
    private fun anyDividendEditInProgress() =
        editingUkEntryId != null || editingScalarEntryId != null || editingForeignEntryId != null

    private fun refreshTaxYearSelectorEnabled() {
        taxYearSelector.root.isDisable = anyDividendEditInProgress()
    }

    // -------------------------------------------------------------------------
    // UK dividends from companies and funds
    // -------------------------------------------------------------------------

    private fun buildUkEntryForm(): VBox {
        ukCategoryPicker.apply {
            items.setAll(*DividendCategory.entries.toTypedArray())
            promptText = "Dividend type"
            prefWidth  = 280.0
            buttonCell = ukCategoryCell()
            setCellFactory { ukCategoryCell() }
        }
        ukAmountField.apply { promptText = "Amount (£)";       prefWidth = 120.0 }
        ukDescField.apply   { promptText = "Description";      prefWidth = 200.0 }
        ukDateField.apply   { promptText = "Date (YYYY-MM-DD)"; prefWidth = 170.0 }

        val newAddBtn = Button("Add").apply {
            styleClass.add("primary-action-button")
            setOnAction { if (editingUkEntryId != null) handleSaveUkEdit() else handleUkAdd() }
        }
        ukAddBtn = newAddBtn

        val newCancelEditBtn = Button("Cancel edit").apply {
            styleClass.add("secondary-action-button")
            isVisible = false
            isManaged = false
            setOnAction { exitUkEditMode() }
        }
        ukCancelEditBtn = newCancelEditBtn

        return entryFormCard(ukFormHeading,
            HBox(10.0).apply {
                alignment = Pos.CENTER_LEFT
                children.addAll(
                    ukCategoryPicker,
                    infoPopup(
                        "Select 'Dividends from UK companies' for cash dividends from UK-listed " +
                                "companies and investment trusts.\n\nSelect 'Dividends from UK funds' for " +
                                "amounts on tax certificates from fund platforms such as Vanguard or iShares " +
                                "(unit trusts and OEICs)."
                    ),
                    ukAmountField,
                    infoPopup("The dividend amount in pounds, as shown on your dividend voucher or tax certificate."),
                    ukDescField,
                    infoPopup("A note to help you identify this entry, e.g. 'Lloyds Banking Group Q2 dividend'."),
                    ukDateField,
                    infoPopup("The date the dividend was paid, in format YYYY-MM-DD (e.g. 2025-07-15)."),
                    newAddBtn,
                    newCancelEditBtn,
                )
            }
        )
    }

    private fun handleUkAdd() {
        val amountText = ukAmountField.text.trim()
        val amount     = amountText.toDoubleOrNull()
        val category   = ukCategoryPicker.value
        val dateText   = ukDateField.text.trim()
        val desc       = ukDescField.text.trim()
        val errors     = mutableListOf<String>()

        if (category == null)            errors += "Please select a dividend type."
        if (amountText.isBlank())        errors += "Please enter an amount."
        else if (amount == null)         errors += "Amount must be a number (e.g. 500.00)."
        else if (amount <= 0)            errors += "Amount must be greater than zero."
        if (desc.isBlank())              errors += "Please enter a description."
        if (dateText.isBlank())          errors += "Please enter a transaction date."
        else if (!isValidDate(dateText)) errors += "Date must be in format YYYY-MM-DD."

        if (errors.isNotEmpty()) { Dialogs.showError(errors.joinToString("\n"), title = "Validation Error"); return }

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
                val entry = IncomeDividendRepository.recordDividend(
                    userId          = userId,
                    taxYear         = derivedTaxYear,
                    category        = category!!.dbKey,
                    amount          = amount!!,
                    description     = desc,
                    transactionDate = dateText,
                )
                kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                    ukEntries.add(entry)
                    refreshUkTotal()
                    clearUkForm()
                    onStatusChange("UK dividend entry added ✓")
                }
            } catch (e: FinalDeclarationLockedException) {
                kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                    Dialogs.showError(e.message ?: "This tax year can no longer be amended.", title = "Tax year locked")
                    loadAllEntries()
                }
            }
        }
    }

    // ── UK dividends — Edit ──────────────────────────────────────────────

    private fun handleEditUkSelected(table: TableView<IncomeDividendEntry>) {
        val selected = table.selectionModel.selectedItem
        if (selected == null) {
            Dialogs.showError("Please select an entry to edit.")
            return
        }
        enterUkEditMode(selected)
    }

    private fun enterUkEditMode(entry: IncomeDividendEntry) {
        editingUkEntryId = entry.id

        ukCategoryPicker.value = DividendCategory.entries.firstOrNull { it.dbKey == entry.category }
        ukAmountField.text     = "%.2f".format(entry.amount)
        ukDescField.text       = entry.description
        ukDateField.text       = entry.transactionDate

        ukFormHeading.text        = "Edit entry"
        ukAddBtn?.text            = "Save changes"
        ukCancelEditBtn?.isVisible = true
        ukCancelEditBtn?.isManaged = true
        ukEditBtn?.isDisable      = true
        ukDeleteBtn?.isDisable    = true

        refreshTaxYearSelectorEnabled()
    }

    private fun exitUkEditMode() {
        editingUkEntryId = null
        ukFormHeading.text        = "New entry"
        ukAddBtn?.text            = "Add"
        ukCancelEditBtn?.isVisible = false
        ukCancelEditBtn?.isManaged = false
        ukEditBtn?.isDisable      = false
        ukDeleteBtn?.isDisable    = false
        clearUkForm()
        refreshTaxYearSelectorEnabled()
    }

    private fun handleSaveUkEdit() {
        val existingId = editingUkEntryId ?: return

        val amountText = ukAmountField.text.trim()
        val amount     = amountText.toDoubleOrNull()
        val category   = ukCategoryPicker.value
        val dateText   = ukDateField.text.trim()
        val desc       = ukDescField.text.trim()
        val errors     = mutableListOf<String>()

        if (category == null)            errors += "Please select a dividend type."
        if (amountText.isBlank())        errors += "Please enter an amount."
        else if (amount == null)         errors += "Amount must be a number (e.g. 500.00)."
        else if (amount <= 0)            errors += "Amount must be greater than zero."
        if (desc.isBlank())              errors += "Please enter a description."
        if (dateText.isBlank())          errors += "Please enter a transaction date."
        else if (!isValidDate(dateText)) errors += "Date must be in format YYYY-MM-DD."

        if (errors.isNotEmpty()) { Dialogs.showError(errors.joinToString("\n"), title = "Validation Error"); return }

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
                val edited = IncomeDividendRepository.edit(
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
                    // with a new id, so replace by index rather than by id.
                    val idx = ukEntries.indexOfFirst { it.id == existingId }
                    if (idx >= 0) ukEntries[idx] = edited else ukEntries.add(edited)
                    refreshUkTotal()
                    exitUkEditMode()
                    onStatusChange("UK dividend entry updated ✓")
                }
            } catch (e: FinalDeclarationLockedException) {
                kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                    Dialogs.showError(e.message ?: "This tax year can no longer be amended.", title = "Tax year locked")
                    exitUkEditMode()
                    loadAllEntries()
                }
            }
        }
    }

    private fun buildUkEntriesTable(): VBox {
        val table = TableView<IncomeDividendEntry>(ukEntries).apply {
            prefHeight = 200.0
            minHeight  = 200.0
            placeholder = wrappingLabel("No entries for this tax year")
            columns.addAll(
                TableColumn<IncomeDividendEntry, String>("Date").apply {
                    prefWidth = 110.0
                    setCellValueFactory { SimpleStringProperty(it.value.transactionDate) }
                },
                TableColumn<IncomeDividendEntry, String>("Type").apply {
                    prefWidth = 250.0
                    setCellValueFactory {
                        SimpleStringProperty(
                            DividendCategory.entries
                                .firstOrNull { c -> c.dbKey == it.value.category }?.label
                                ?: it.value.category
                        )
                    }
                },
                TableColumn<IncomeDividendEntry, String>("Description").apply {
                    prefWidth = 200.0
                    setCellValueFactory { SimpleStringProperty(it.value.description) }
                },
                TableColumn<IncomeDividendEntry, String>("Amount").apply {
                    prefWidth = 110.0
                    style = "-fx-alignment: CENTER-RIGHT;"
                    setCellValueFactory { SimpleStringProperty("£%.2f".format(it.value.amount)) }
                },
            )
        }
        val newEditBtn = Button("Edit selected").apply {
            styleClass.add("primary-action-button")
            setOnAction { handleEditUkSelected(table) }
        }
        ukEditBtn = newEditBtn

        val newDeleteBtn = Button("Delete selected").apply {
            styleClass.add("primary-action-button")
            setOnAction { deleteUkSelected(table) }
        }
        ukDeleteBtn = newDeleteBtn
        return entryFormCard("Entries", table, HBox(10.0, newEditBtn, newDeleteBtn))
    }

    private fun deleteUkSelected(table: TableView<IncomeDividendEntry>) {
        val sel = table.selectionModel.selectedItem
            ?: return Dialogs.showError("Please select an entry to delete.").let {}
        if (!Dialogs.showConfirmation(
                message    = "Delete this UK dividend entry?",
                title      = "Delete entry",
                headerText = "Are you sure?",
            )
        ) return
        scope.launch(Dispatchers.IO) {
            try {
                IncomeDividendRepository.delete(sel.id)
                kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                    ukEntries.remove(sel); refreshUkTotal(); onStatusChange("Entry deleted")
                }
            } catch (e: FinalDeclarationLockedException) {
                kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                    Dialogs.showError(e.message ?: "This tax year can no longer be amended.", title = "Tax year locked")
                    loadAllEntries()
                }
            }
        }
    }

    private fun refreshUkTotal() {
        ukTotalLabel.text = "£%.2f".format(ukEntries.sumOf { it.amount })
    }

    private fun clearUkForm() {
        ukCategoryPicker.value = null
        ukAmountField.clear(); ukDescField.clear(); ukDateField.clear()
    }

    private fun ukCategoryCell() = object : ListCell<DividendCategory>() {
        override fun updateItem(item: DividendCategory?, empty: Boolean) {
            super.updateItem(item, empty)
            text = if (empty || item == null) null else item.label
        }
    }

    // -------------------------------------------------------------------------
    // UK dividends — special types
    // -------------------------------------------------------------------------

    private fun buildScalarEntryForm(): VBox {
        scalarCategoryPicker.apply {
            items.setAll(*DividendScalarCategory.entries.toTypedArray())
            promptText = "Type"
            prefWidth  = 280.0
            buttonCell = scalarCategoryCell()
            setCellFactory { scalarCategoryCell() }
        }
        scalarAmountField.apply { promptText = "Gross amount";              prefWidth = 140.0 }
        scalarRefField.apply    { promptText = "Reference"; prefWidth = 220.0 }
        scalarDateField.apply   { promptText = "Date (YYYY-MM-DD)";             prefWidth = 170.0 }

        val newAddBtn = Button("Add").apply {
            styleClass.add("primary-action-button")
            setOnAction { if (editingScalarEntryId != null) handleSaveScalarEdit() else handleScalarAdd() }
        }
        scalarAddBtn = newAddBtn

        val newCancelEditBtn = Button("Cancel edit").apply {
            styleClass.add("secondary-action-button")
            isVisible = false
            isManaged = false
            setOnAction { exitScalarEditMode() }
        }
        scalarCancelEditBtn = newCancelEditBtn

        return entryFormCard(scalarFormHeading,
            HBox(10.0).apply {
                alignment = Pos.CENTER_LEFT
                children.addAll(
                    scalarCategoryPicker,
                    infoPopup(
                        "Select 'Stock Dividend' when a company issues new shares instead of paying cash. "+
                                "The shareholder receives shares whose market value is taxable as income." +
                                "\n\n" +
                                "Select 'Redeemable shares' for proceeds from shares that are redeemed by the " +
                                "issuing company, treated as income rather than a capital gain in certain " +
                                "circumstances." +
                                "\n\n" +
                                "Select 'Bonus issue of securities' for bonus shares or securities issued by a " +
                                "company, taxable as income where HMRC deems them to have a cash equivalent." +
                                "\n\n" +
                                "Select 'Close company loans written off' when a close company (broadly, a private " +
                                "company controlled by five or fewer shareholders) writes off a loan it made to a " +
                                "participator (shareholder/director). The written-off amount is treated as a deemed " +
                                "dividend."
                    ),
                    scalarAmountField,
                    infoPopup("Gross amount (£)."),
                    scalarRefField,
                    infoPopup("A note to help you identify this entry, e.g. 'Lloyds Banking Group Q2 dividend'."),
                    scalarDateField,
                    infoPopup("The date the dividend was paid, in format YYYY-MM-DD (e.g. 2025-07-15)."),
                    newAddBtn,
                    newCancelEditBtn,
                )
            }
        )
    }

    private fun handleScalarAdd() {
        val amountText = scalarAmountField.text.trim()
        val amount     = amountText.toDoubleOrNull()
        val category   = scalarCategoryPicker.value
        val ref        = scalarRefField.text.trim().ifBlank { null }
        val dateText   = scalarDateField.text.trim()
        val errors     = mutableListOf<String>()

        if (category == null)            errors += "Please select a type."
        if (amountText.isBlank())        errors += "Please enter a gross amount."
        else if (amount == null)         errors += "Gross amount must be a number (e.g. 500.00)."
        else if (amount <= 0)            errors += "Gross amount must be greater than zero."
        if (dateText.isBlank())          errors += "Please enter a transaction date."
        else if (!isValidDate(dateText)) errors += "Date must be in format YYYY-MM-DD."

        if (errors.isNotEmpty()) { Dialogs.showError(errors.joinToString("\n"), title = "Validation Error"); return }

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
                val entry = IncomeDividendRepository.recordDividend(
                    userId            = userId,
                    taxYear           = derivedTaxYear,
                    category          = category!!.dbKey,
                    amount            = amount!!,
                    customerReference = ref,
                    description       = ref ?: "",
                    transactionDate   = dateText,
                )
                kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                    scalarEntries.add(entry)
                    refreshScalarTotal()
                    clearScalarForm()
                    onStatusChange("Dividend entry added ✓")
                }
            } catch (e: FinalDeclarationLockedException) {
                kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                    Dialogs.showError(e.message ?: "This tax year can no longer be amended.", title = "Tax year locked")
                    loadAllEntries()
                }
            }
        }
    }

    // ── UK dividends (special types) — Edit ─────────────────────────────

    private fun handleEditScalarSelected(table: TableView<IncomeDividendEntry>) {
        val selected = table.selectionModel.selectedItem
        if (selected == null) {
            Dialogs.showError("Please select an entry to edit.")
            return
        }
        enterScalarEditMode(selected)
    }

    private fun enterScalarEditMode(entry: IncomeDividendEntry) {
        editingScalarEntryId = entry.id

        scalarCategoryPicker.value = DividendScalarCategory.entries.firstOrNull { it.dbKey == entry.category }
        scalarAmountField.text     = "%.2f".format(entry.amount)
        scalarRefField.text        = entry.customerReference ?: ""
        scalarDateField.text       = entry.transactionDate

        scalarFormHeading.text        = "Edit entry"
        scalarAddBtn?.text            = "Save changes"
        scalarCancelEditBtn?.isVisible = true
        scalarCancelEditBtn?.isManaged = true
        scalarEditBtn?.isDisable      = true
        scalarDeleteBtn?.isDisable    = true

        refreshTaxYearSelectorEnabled()
    }

    private fun exitScalarEditMode() {
        editingScalarEntryId = null
        scalarFormHeading.text        = "New entry"
        scalarAddBtn?.text            = "Add"
        scalarCancelEditBtn?.isVisible = false
        scalarCancelEditBtn?.isManaged = false
        scalarEditBtn?.isDisable      = false
        scalarDeleteBtn?.isDisable    = false
        clearScalarForm()
        refreshTaxYearSelectorEnabled()
    }

    private fun handleSaveScalarEdit() {
        val existingId = editingScalarEntryId ?: return

        val amountText = scalarAmountField.text.trim()
        val amount     = amountText.toDoubleOrNull()
        val category   = scalarCategoryPicker.value
        val ref        = scalarRefField.text.trim().ifBlank { null }
        val dateText   = scalarDateField.text.trim()
        val errors     = mutableListOf<String>()

        if (category == null)            errors += "Please select a type."
        if (amountText.isBlank())        errors += "Please enter a gross amount."
        else if (amount == null)         errors += "Gross amount must be a number (e.g. 500.00)."
        else if (amount <= 0)            errors += "Gross amount must be greater than zero."
        if (dateText.isBlank())          errors += "Please enter a transaction date."
        else if (!isValidDate(dateText)) errors += "Date must be in format YYYY-MM-DD."

        if (errors.isNotEmpty()) { Dialogs.showError(errors.joinToString("\n"), title = "Validation Error"); return }

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
                val edited = IncomeDividendRepository.edit(
                    existingId        = existingId,
                    userId            = userId,
                    taxYear           = derivedTaxYear,
                    category          = category!!.dbKey,
                    amount            = amount!!,
                    customerReference = ref,
                    description       = ref ?: "",
                    transactionDate   = dateText,
                )
                kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                    val idx = scalarEntries.indexOfFirst { it.id == existingId }
                    if (idx >= 0) scalarEntries[idx] = edited else scalarEntries.add(edited)
                    refreshScalarTotal()
                    exitScalarEditMode()
                    onStatusChange("Dividend entry updated ✓")
                }
            } catch (e: FinalDeclarationLockedException) {
                kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                    Dialogs.showError(e.message ?: "This tax year can no longer be amended.", title = "Tax year locked")
                    exitScalarEditMode()
                    loadAllEntries()
                }
            }
        }
    }

    private fun buildScalarEntriesTable(): VBox {
        val table = TableView<IncomeDividendEntry>(scalarEntries).apply {
            prefHeight = 200.0
            minHeight  = 200.0
            placeholder = wrappingLabel("No entries for this tax year")
            columns.addAll(
                TableColumn<IncomeDividendEntry, String>("Date").apply {
                    prefWidth = 110.0
                    setCellValueFactory { SimpleStringProperty(it.value.transactionDate) }
                },
                TableColumn<IncomeDividendEntry, String>("Type").apply {
                    prefWidth = 250.0
                    setCellValueFactory {
                        SimpleStringProperty(
                            DividendScalarCategory.entries
                                .firstOrNull { c -> c.dbKey == it.value.category }?.label
                                ?: it.value.category
                        )
                    }
                },
                TableColumn<IncomeDividendEntry, String>("Customer reference").apply {
                    prefWidth = 200.0
                    setCellValueFactory { SimpleStringProperty(it.value.customerReference ?: "") }
                },
                TableColumn<IncomeDividendEntry, String>("Gross amount").apply {
                    prefWidth = 120.0
                    style = "-fx-alignment: CENTER-RIGHT;"
                    setCellValueFactory { SimpleStringProperty("£%.2f".format(it.value.amount)) }
                },
            )
        }
        val newEditBtn = Button("Edit selected").apply {
            styleClass.add("primary-action-button")
            setOnAction { handleEditScalarSelected(table) }
        }
        scalarEditBtn = newEditBtn

        val newDeleteBtn = Button("Delete selected").apply {
            styleClass.add("primary-action-button")
            setOnAction { deleteScalarSelected(table) }
        }
        scalarDeleteBtn = newDeleteBtn
        return entryFormCard("Entries", table, HBox(10.0, newEditBtn, newDeleteBtn))
    }

    private fun deleteScalarSelected(table: TableView<IncomeDividendEntry>) {
        val sel = table.selectionModel.selectedItem
            ?: return Dialogs.showError("Please select an entry to delete.").let {}
        if (!Dialogs.showConfirmation(
                message    = "Delete this dividend entry?",
                title      = "Delete entry",
                headerText = "Are you sure?",
            )
        ) return
        scope.launch(Dispatchers.IO) {
            try {
                IncomeDividendRepository.delete(sel.id)
                kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                    scalarEntries.remove(sel); refreshScalarTotal(); onStatusChange("Entry deleted")
                }
            } catch (e: FinalDeclarationLockedException) {
                kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                    Dialogs.showError(e.message ?: "This tax year can no longer be amended.", title = "Tax year locked")
                    loadAllEntries()
                }
            }
        }
    }

    private fun refreshScalarTotal() {
        scalarTotalLabel.text = "£%.2f".format(scalarEntries.sumOf { it.amount })
    }

    private fun clearScalarForm() {
        scalarCategoryPicker.value = null
        scalarAmountField.clear(); scalarRefField.clear(); scalarDateField.clear()
    }

    private fun scalarCategoryCell() = object : ListCell<DividendScalarCategory>() {
        override fun updateItem(item: DividendScalarCategory?, empty: Boolean) {
            super.updateItem(item, empty)
            text = if (empty || item == null) null else item.label
        }
    }

    // -------------------------------------------------------------------------
    // Foreign dividends
    // -------------------------------------------------------------------------

    private fun buildForeignEntryForm(): VBox {
        foreignCategoryPicker.apply {
            items.setAll(*DividendForeignCategory.entries.toTypedArray())
            promptText = "Type"
            prefWidth  = 300.0
            buttonCell = foreignCategoryCell()
            setCellFactory { foreignCategoryCell() }
        }
        foreignAmountBeforeField.apply { promptText = "£ before tax";   prefWidth = 150.0 }
        foreignTaxTakenField.apply     { promptText = "£ tax taken";     prefWidth = 140.0 }
        foreignSwtField.apply          { promptText = "£ SWT"; prefWidth = 155.0 }
        foreignTaxableField.apply      { promptText = "£ taxable";    prefWidth = 145.0 }
        foreignDateField.apply         { promptText = "YYYY-MM-DD";     prefWidth = 150.0 }
        foreignCountryField.apply      { promptText = "Example FRA";    prefWidth = 140.0 }

        val newAddBtn = Button("Add").apply {
            styleClass.add("primary-action-button")
            setOnAction { if (editingForeignEntryId != null) handleSaveForeignEdit() else handleForeignAdd() }
        }
        foreignAddBtn = newAddBtn

        val newCancelEditBtn = Button("Cancel edit").apply {
            styleClass.add("secondary-action-button")
            isVisible = false
            isManaged = false
            setOnAction { exitForeignEditMode() }
        }
        foreignCancelEditBtn = newCancelEditBtn

        return entryFormCard(foreignFormHeading,
            VBox(8.0).apply {
                children.addAll(
                    HBox(10.0).apply {
                        alignment = Pos.CENTER_LEFT
                        children.addAll(
                            foreignCategoryPicker,
                            foreignCountryField,
                            infoPopup("Three letter country code, for example FRA"),
                            foreignDateField,
                            infoPopup("The date the dividend was paid, in format YYYY-MM-DD (e.g. 2025-07-15)."),
                        )
                    },
                    HBox(10.0).apply {
                        alignment = Pos.CENTER_LEFT
                        children.addAll(
                            foreignAmountBeforeField,
                            infoPopup("Optional. Amount before tax (£)"),
                            foreignTaxTakenField,
                            infoPopup("Optional. Foreign tax deducted by the country of origin of the payment (£)"),
                            foreignSwtField,
                            infoPopup(
                                "Optional. Special Withholding Tax (SWT) is an amount of tax withheld on " +
                                        "certain payments to UK residents under the terms of the European Savings " +
                                        "Directive and equivalent third party agreements. This tax will be in " +
                                        "addition to any foreign tax deducted by the country of origin of the " +
                                        "payment. (£)"
                            ),
                            foreignTaxableField,
                            infoPopup("Taxable amount (£)"),
                        )
                    },
                    HBox(10.0).apply {
                        alignment = Pos.CENTER_LEFT
                        children.addAll(
                            foreignFtcrCheck,
                            infoPopup(
                                "Tick 'foreign tax credit relief claimed' only if you are claiming relief for " +
                                        "overseas tax already paid — confirm with your tax adviser if unsure."
                            ),
                            newAddBtn,
                            newCancelEditBtn,
                        )
                    },
                )
            }
        )
    }

    private fun handleForeignAdd() {
        val category      = foreignCategoryPicker.value
        val countryRaw    = foreignCountryField.text.trim().uppercase()
        val dateText      = foreignDateField.text.trim()
        val amtBeforeText = foreignAmountBeforeField.text.trim()
        val taxTakenText  = foreignTaxTakenField.text.trim()
        val swtText       = foreignSwtField.text.trim()
        val taxableText   = foreignTaxableField.text.trim()
        val ftcr          = foreignFtcrCheck.isSelected

        val amtBefore = amtBeforeText.toDoubleOrNull()
        val taxTaken  = taxTakenText.toDoubleOrNull()
        val swt       = swtText.toDoubleOrNull()
        val taxable   = taxableText.toDoubleOrNull()

        val errors = mutableListOf<String>()
        if (category == null)    errors += "Please select a type."
        if (countryRaw.isBlank()) errors += "Please enter a country code."
        else if (countryRaw.length != 3 || !countryRaw.all { it.isLetter() })
            errors += "Country code must be exactly 3 letters (e.g. FRA, DEU, USA)."
        if (dateText.isBlank())           errors += "Please enter a transaction date."
        else if (!isValidDate(dateText))  errors += "Date must be in format YYYY-MM-DD."
        if (amtBeforeText.isNotBlank() && amtBefore == null) errors += "Amount before tax must be a number."
        if (taxTakenText.isNotBlank()  && taxTaken  == null) errors += "Tax taken off must be a number."
        if (swtText.isNotBlank()       && swt       == null) errors += "Special withholding tax must be a number."
        if (taxableText.isBlank())        errors += "Please enter the taxable amount."
        else if (taxable == null)         errors += "Taxable amount must be a number."
        else if (taxable <= 0)            errors += "Taxable amount must be greater than zero."

        if (errors.isNotEmpty()) { Dialogs.showError(errors.joinToString("\n"), title = "Validation Error"); return }

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
                val entry = IncomeDividendForeignRepository.record(
                    userId                 = userId,
                    taxYear                = derivedTaxYear,
                    category               = category!!.dbKey,
                    countryCode            = countryRaw,
                    amountBeforeTax        = amtBefore,
                    taxTakenOff            = taxTaken,
                    specialWithholdingTax  = swt,
                    foreignTaxCreditRelief = ftcr,
                    taxableAmount          = taxable!!,
                    transactionDate        = dateText,
                )
                kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                    foreignEntries.add(entry)
                    refreshForeignTotal()
                    clearForeignForm()
                    onStatusChange("Foreign dividend entry added ✓")
                }
            } catch (e: FinalDeclarationLockedException) {
                kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                    Dialogs.showError(e.message ?: "This tax year can no longer be amended.", title = "Tax year locked")
                    loadAllEntries()
                }
            }
        }
    }

    // ── Foreign dividends — Edit ─────────────────────────────────────────

    private fun handleEditForeignSelected(table: TableView<IncomeDividendForeignEntry>) {
        val selected = table.selectionModel.selectedItem
        if (selected == null) {
            Dialogs.showError("Please select an entry to edit.")
            return
        }
        enterForeignEditMode(selected)
    }

    private fun enterForeignEditMode(entry: IncomeDividendForeignEntry) {
        editingForeignEntryId = entry.id

        foreignCategoryPicker.value    = DividendForeignCategory.entries.firstOrNull { it.dbKey == entry.category }
        foreignCountryField.text       = entry.countryCode
        foreignDateField.text          = entry.transactionDate
        foreignAmountBeforeField.text  = entry.amountBeforeTax?.let { "%.2f".format(it) } ?: ""
        foreignTaxTakenField.text      = entry.taxTakenOff?.let { "%.2f".format(it) } ?: ""
        foreignSwtField.text           = entry.specialWithholdingTax?.let { "%.2f".format(it) } ?: ""
        foreignTaxableField.text       = "%.2f".format(entry.taxableAmount)
        foreignFtcrCheck.isSelected    = entry.foreignTaxCreditRelief

        foreignFormHeading.text        = "Edit entry"
        foreignAddBtn?.text            = "Save changes"
        foreignCancelEditBtn?.isVisible = true
        foreignCancelEditBtn?.isManaged = true
        foreignEditBtn?.isDisable      = true
        foreignDeleteBtn?.isDisable    = true

        refreshTaxYearSelectorEnabled()
    }

    private fun exitForeignEditMode() {
        editingForeignEntryId = null
        foreignFormHeading.text        = "New entry"
        foreignAddBtn?.text            = "Add"
        foreignCancelEditBtn?.isVisible = false
        foreignCancelEditBtn?.isManaged = false
        foreignEditBtn?.isDisable      = false
        foreignDeleteBtn?.isDisable    = false
        clearForeignForm()
        refreshTaxYearSelectorEnabled()
    }

    private fun handleSaveForeignEdit() {
        val existingId = editingForeignEntryId ?: return

        val category      = foreignCategoryPicker.value
        val countryRaw    = foreignCountryField.text.trim().uppercase()
        val dateText      = foreignDateField.text.trim()
        val amtBeforeText = foreignAmountBeforeField.text.trim()
        val taxTakenText  = foreignTaxTakenField.text.trim()
        val swtText       = foreignSwtField.text.trim()
        val taxableText   = foreignTaxableField.text.trim()
        val ftcr          = foreignFtcrCheck.isSelected

        val amtBefore = amtBeforeText.toDoubleOrNull()
        val taxTaken  = taxTakenText.toDoubleOrNull()
        val swt       = swtText.toDoubleOrNull()
        val taxable   = taxableText.toDoubleOrNull()

        val errors = mutableListOf<String>()
        if (category == null)    errors += "Please select a type."
        if (countryRaw.isBlank()) errors += "Please enter a country code."
        else if (countryRaw.length != 3 || !countryRaw.all { it.isLetter() })
            errors += "Country code must be exactly 3 letters (e.g. FRA, DEU, USA)."
        if (dateText.isBlank())           errors += "Please enter a transaction date."
        else if (!isValidDate(dateText))  errors += "Date must be in format YYYY-MM-DD."
        if (amtBeforeText.isNotBlank() && amtBefore == null) errors += "Amount before tax must be a number."
        if (taxTakenText.isNotBlank()  && taxTaken  == null) errors += "Tax taken off must be a number."
        if (swtText.isNotBlank()       && swt       == null) errors += "Special withholding tax must be a number."
        if (taxableText.isBlank())        errors += "Please enter the taxable amount."
        else if (taxable == null)         errors += "Taxable amount must be a number."
        else if (taxable <= 0)            errors += "Taxable amount must be greater than zero."

        if (errors.isNotEmpty()) { Dialogs.showError(errors.joinToString("\n"), title = "Validation Error"); return }

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
                val edited = IncomeDividendForeignRepository.edit(
                    existingId             = existingId,
                    userId                 = userId,
                    taxYear                = derivedTaxYear,
                    category               = category!!.dbKey,
                    countryCode            = countryRaw,
                    amountBeforeTax        = amtBefore,
                    taxTakenOff            = taxTaken,
                    specialWithholdingTax  = swt,
                    foreignTaxCreditRelief = ftcr,
                    taxableAmount          = taxable!!,
                    transactionDate        = dateText,
                )
                kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                    val idx = foreignEntries.indexOfFirst { it.id == existingId }
                    if (idx >= 0) foreignEntries[idx] = edited else foreignEntries.add(edited)
                    refreshForeignTotal()
                    exitForeignEditMode()
                    onStatusChange("Foreign dividend entry updated ✓")
                }
            } catch (e: FinalDeclarationLockedException) {
                kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                    Dialogs.showError(e.message ?: "This tax year can no longer be amended.", title = "Tax year locked")
                    exitForeignEditMode()
                    loadAllEntries()
                }
            }
        }
    }

    private fun buildForeignEntriesTable(): VBox {
        val table = TableView<IncomeDividendForeignEntry>(foreignEntries).apply {
            prefHeight = 220.0
            minHeight  = 220.0
            placeholder = wrappingLabel("No foreign dividend entries for this tax year")
            columns.addAll(
                TableColumn<IncomeDividendForeignEntry, String>("Date").apply {
                    prefWidth = 110.0
                    setCellValueFactory { SimpleStringProperty(it.value.transactionDate) }
                },
                TableColumn<IncomeDividendForeignEntry, String>("Type").apply {
                    prefWidth = 220.0
                    setCellValueFactory {
                        SimpleStringProperty(
                            DividendForeignCategory.entries
                                .firstOrNull { c -> c.dbKey == it.value.category }?.label
                                ?: it.value.category
                        )
                    }
                },
                TableColumn<IncomeDividendForeignEntry, String>("Country").apply {
                    prefWidth = 70.0
                    setCellValueFactory { SimpleStringProperty(it.value.countryCode) }
                },
                TableColumn<IncomeDividendForeignEntry, String>("Amt before tax").apply {
                    prefWidth = 130.0
                    style = "-fx-alignment: CENTER-RIGHT;"
                    setCellValueFactory {
                        SimpleStringProperty(it.value.amountBeforeTax?.let { v -> "£%.2f".format(v) } ?: "—")
                    }
                },
                TableColumn<IncomeDividendForeignEntry, String>("Tax taken off").apply {
                    prefWidth = 120.0
                    style = "-fx-alignment: CENTER-RIGHT;"
                    setCellValueFactory {
                        SimpleStringProperty(it.value.taxTakenOff?.let { v -> "£%.2f".format(v) } ?: "—")
                    }
                },
                TableColumn<IncomeDividendForeignEntry, String>("SWT").apply {
                    prefWidth = 100.0
                    style = "-fx-alignment: CENTER-RIGHT;"
                    setCellValueFactory {
                        SimpleStringProperty(it.value.specialWithholdingTax?.let { v -> "£%.2f".format(v) } ?: "—")
                    }
                },
                TableColumn<IncomeDividendForeignEntry, String>("FTCR").apply {
                    prefWidth = 55.0
                    setCellValueFactory {
                        SimpleStringProperty(if (it.value.foreignTaxCreditRelief) "Yes" else "No")
                    }
                },
                TableColumn<IncomeDividendForeignEntry, String>("Taxable").apply {
                    prefWidth = 110.0
                    style = "-fx-alignment: CENTER-RIGHT;"
                    setCellValueFactory { SimpleStringProperty("£%.2f".format(it.value.taxableAmount)) }
                },
            )
        }
        val newEditBtn = Button("Edit selected").apply {
            styleClass.add("primary-action-button")
            setOnAction { handleEditForeignSelected(table) }
        }
        foreignEditBtn = newEditBtn

        val newDeleteBtn = Button("Delete selected").apply {
            styleClass.add("primary-action-button")
            setOnAction { deleteForeignSelected(table) }
        }
        foreignDeleteBtn = newDeleteBtn
        return entryFormCard("Entries", table, HBox(10.0, newEditBtn, newDeleteBtn))
    }

    private fun deleteForeignSelected(table: TableView<IncomeDividendForeignEntry>) {
        val sel = table.selectionModel.selectedItem
            ?: return Dialogs.showError("Please select an entry to delete.").let {}
        if (!Dialogs.showConfirmation(
                message    = "Delete this foreign dividend entry?",
                title      = "Delete entry",
                headerText = "Are you sure?",
            )
        ) return
        scope.launch(Dispatchers.IO) {
            try {
                IncomeDividendForeignRepository.delete(sel.id)
                kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                    foreignEntries.remove(sel); refreshForeignTotal(); onStatusChange("Entry deleted")
                }
            } catch (e: FinalDeclarationLockedException) {
                kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                    Dialogs.showError(e.message ?: "This tax year can no longer be amended.", title = "Tax year locked")
                    loadAllEntries()
                }
            }
        }
    }

    private fun refreshForeignTotal() {
        foreignTotalLabel.text = "£%.2f".format(foreignEntries.sumOf { it.taxableAmount })
    }

    private fun clearForeignForm() {
        foreignCategoryPicker.value = null
        foreignCountryField.clear(); foreignDateField.clear()
        foreignAmountBeforeField.clear(); foreignTaxTakenField.clear()
        foreignSwtField.clear(); foreignTaxableField.clear()
        foreignFtcrCheck.isSelected = false
    }

    private fun foreignCategoryCell() = object : ListCell<DividendForeignCategory>() {
        override fun updateItem(item: DividendForeignCategory?, empty: Boolean) {
            super.updateItem(item, empty)
            text = if (empty || item == null) null else item.label
        }
    }

    // -------------------------------------------------------------------------
    // Load
    // -------------------------------------------------------------------------

    private fun loadAllEntries() {
        val generation = ++loadGeneration
        scope.launch(Dispatchers.IO) {
            val allUk      = IncomeDividendRepository.currentDividendsForYear(userId, currentTaxYear)
            val ukDbKeys   = DividendCategory.entries.map { it.dbKey }.toSet()
            val scalarKeys = DividendScalarCategory.entries.map { it.dbKey }.toSet()

            val uk      = allUk.filter { it.category in ukDbKeys }
            val scalar  = allUk.filter { it.category in scalarKeys }
            val unknown = allUk.filter { it.category !in ukDbKeys && it.category !in scalarKeys }
            if (unknown.isNotEmpty()) {
                logger.warn { "Dividend entries with unrecognised category keys: ${unknown.map { it.category }.distinct()}" }
            }
            val foreign = IncomeDividendForeignRepository.currentEntriesForYear(userId, currentTaxYear)
            val locked  = SubmissionRepository.isFinalDeclared(userId, currentTaxYear)

            kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                // Discard this result if a newer reload has been requested
                // since this one started — see loadGeneration's doc comment.
                if (generation != loadGeneration) return@withContext

                ukEntries.setAll(uk)
                scalarEntries.setAll(scalar)
                foreignEntries.setAll(foreign)
                refreshUkTotal(); refreshScalarTotal(); refreshForeignTotal()
                applyLock(isFinalDeclared = locked, taxYear = currentTaxYear)
                onStatusChange("Loaded dividend income for $currentTaxYear")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private fun buildTotalBar(label: Label) = HBox(24.0).apply {
        padding   = Insets(12.0, 16.0, 12.0, 16.0)
        styleClass.add("total-bar")
        style     = "-fx-border-radius: 6; -fx-background-radius: 6;"
        alignment = Pos.CENTER_LEFT
        children.addAll(
            wrappingLabel("Year total:").apply { style = "-fx-font-weight: bold;" },
            label,
        )
    }

    private fun entryFormCard(heading: String, vararg content: javafx.scene.Node): VBox =
        entryFormCard(wrappingLabel(heading).apply { style = "-fx-font-weight: bold;" }, *content)

    private fun entryFormCard(headingLabel: Label, vararg content: javafx.scene.Node): VBox =
        VBox(8.0).apply {
            padding = Insets(12.0, 16.0, 12.0, 16.0)
            styleClass.add("content-card")
            style   = "-fx-border-radius: 8; -fx-background-radius: 8;"
            children.addAll(
                headingLabel,
                Separator(),
                *content,
            )
        }

    private fun isValidDate(text: String) =
        try { LocalDate.parse(text); true } catch (_: DateTimeParseException) { false }
}
