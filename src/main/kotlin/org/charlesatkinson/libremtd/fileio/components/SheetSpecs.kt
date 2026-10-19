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

package org.charlesatkinson.libremtd.fileio.components

import org.charlesatkinson.libremtd.database.ExpensePropertyForeignRepository
import org.charlesatkinson.libremtd.database.ExpensePropertyUkRepository
import org.charlesatkinson.libremtd.database.IncomeDividendForeignRepository
import org.charlesatkinson.libremtd.database.IncomeDividendRepository
import org.charlesatkinson.libremtd.database.IncomePropertyForeignRepository
import org.charlesatkinson.libremtd.database.IncomePropertyUkRepository
import org.charlesatkinson.libremtd.database.IncomeSavingsRepository
import org.charlesatkinson.libremtd.database.PeriodRepository
import org.charlesatkinson.libremtd.database.PropertyType
import org.charlesatkinson.libremtd.ui.DividendCategory
import org.charlesatkinson.libremtd.ui.DividendForeignCategory
import org.charlesatkinson.libremtd.ui.DividendScalarCategory
import org.charlesatkinson.libremtd.ui.ExpenseCategory
import org.charlesatkinson.libremtd.ui.ForeignExpenseCategory
import org.charlesatkinson.libremtd.ui.ForeignIncomeCategory
import org.charlesatkinson.libremtd.ui.IncomeCategory
import org.charlesatkinson.libremtd.ui.SavingsCategory

// ---------------------------------------------------------------------------
// Sheets
// ---------------------------------------------------------------------------

/**
 * One entry per spreadsheet sheet. [label] is both the sheet name inside the
 * workbook (31 characters or fewer, which is Excel's limit) and the text
 * shown in the Export pane.
 *
 * The declaration order matters: it is the order of the Export pane's table
 * selector and of the sheets in an "all tables" export, and it deliberately
 * follows the order of the main window's side pane (Income: dividends,
 * UK property, foreign property, savings; then Expenses: UK property,
 * foreign property). The three dividend sheets follow the three sections of
 * the dividends pane.
 */
enum class ExportTable(val label: String, val fileName: String) {
    INCOME_DIVIDENDS("Income (dividends)", "income_dividends"),
    INCOME_DIVIDENDS_SPECIAL("Income (dividends, special)", "income_dividends_special"),
    INCOME_DIVIDENDS_FOREIGN("Income (dividends, foreign)", "income_dividends_foreign"),
    INCOME_PROPERTY_UK("Income (property, UK)", "income_property_uk"),
    INCOME_PROPERTY_FOREIGN("Income (property, foreign)", "income_property_foreign"),
    INCOME_SAVINGS("Income (savings)", "income_savings"),
    EXPENSES_PROPERTY_UK("Expenses (property, UK)", "expenses_property_uk"),
    EXPENSES_PROPERTY_FOREIGN("Expenses (property, foreign)", "expenses_property_foreign"),
}

enum class ColumnType {
    TEXT,
    MONEY,
    DATE,
    INTEGER,

    /** A dropdown of human-readable category labels, stored as dbKeys. */
    CATEGORY,

    /** A dropdown of property display strings, resolved to a property id. */
    PROPERTY,

    /** Yes or No, stored as a Boolean. */
    BOOLEAN,

    /** An ISO 3166-1 alpha-3 country code: three letters, stored upper-case. */
    COUNTRY_CODE,
}

/**
 * Describes one column in a sheet.
 *
 * [hidden] columns are written so they survive a round trip, but are hidden
 * in the spreadsheet. The second header row always carries [dbName], so the
 * importer finds columns by name whether or not hidden columns are revealed.
 *
 * [required] means a blank cell is an import error. For MONEY columns a
 * required value must also be greater than zero. A blank, non-required
 * BOOLEAN cell means No.
 */
data class ColumnDef(
    val dbName: String,
    val friendlyName: String,
    val type: ColumnType,
    val required: Boolean = true,
    val readOnly: Boolean = false,
    val hidden: Boolean = false,
    val dropdownValues: List<String>? = null,
)

data class CategoryOption(val label: String, val dbKey: String)

/**
 * Everything the generic exporter and importer need to know about one sheet.
 *
 * Row values are exchanged as maps keyed by [ColumnDef.dbName]. In a map
 * returned by [currentRows], "category" holds the database key, money
 * columns hold Doubles (or null), text columns hold Strings (never null),
 * and "transaction_date" an ISO date string. The importer supplies
 * [insert] and [edit] with the same shape, after validation.
 */
interface SheetSpec {
    val table: ExportTable

    /** Non-null if the sheet has a property column, and the type it accepts. */
    val propertyType: PropertyType?

    val categories: List<CategoryOption>

    /** The keys compared to decide whether an imported row has changed, or
     *  duplicates an existing one. */
    val comparedKeys: List<String>

    fun columns(propertyDisplayStrings: List<String>): List<ColumnDef>

    /** Current (non-superseded) rows for the tax year, in database form. */
    fun currentRows(userId: Int, taxYear: String): List<Map<String, Any?>>

    fun insert(userId: Int, taxYear: String, values: Map<String, Any?>)

    /** Supersedes [existingId] and inserts a replacement. */
    fun edit(existingId: Int, userId: Int, taxYear: String, values: Map<String, Any?>)

    /** Supersedes [id] without a replacement. */
    fun delete(id: Int)
}

internal fun Map<String, Any?>.textValue(key: String): String = this[key] as? String ?: ""
internal fun Map<String, Any?>.moneyValue(key: String): Double = (this[key] as Number).toDouble()
internal fun Map<String, Any?>.optionalMoney(key: String): Double? = (this[key] as? Number)?.toDouble()
internal fun Map<String, Any?>.boolValue(key: String): Boolean = this[key] as? Boolean ?: false
internal fun Map<String, Any?>.intValue(key: String): Int = (this[key] as Number).toInt()

// ---------------------------------------------------------------------------
// Shared layout for the id / [property] / date / category / description /
// amount sheets
// ---------------------------------------------------------------------------

internal data class EntryRow(
    val id: Int,
    val propertyId: Int?,
    val category: String,
    val amount: Double,
    val description: String,
    val transactionDate: String,
)

/**
 * Column names and order follow the GUI's entries tables: Date, Category,
 * Description, Amount. The amount column keeps a "(£)" suffix because the
 * pound sign is not part of the cell values. [categoryHeader] is "Type" on
 * the dividends sheet, as in that GUI table. Where the sheet has a property
 * column it comes first (after the id), standing in for the GUI's property
 * selector above the table.
 */
internal abstract class EntrySheetSpec(
    final override val table: ExportTable,
    final override val propertyType: PropertyType?,
    final override val categories: List<CategoryOption>,
    private val categoryHeader: String = "Category",
) : SheetSpec {

    final override val comparedKeys: List<String> = buildList {
        if (propertyType != null) add("property_id")
        add("transaction_date")
        add("category")
        add("description")
        add("amount")
    }

    abstract fun loadEntries(userId: Int, taxYear: String): List<EntryRow>

    override fun columns(propertyDisplayStrings: List<String>): List<ColumnDef> = buildList {
        add(ColumnDef("id", "ID", ColumnType.INTEGER, readOnly = true))
        if (propertyType != null) {
            add(ColumnDef("property_id", "Property ID", ColumnType.INTEGER, readOnly = true, hidden = true))
            add(ColumnDef("property", "Property", ColumnType.PROPERTY, dropdownValues = propertyDisplayStrings))
        }
        add(ColumnDef("transaction_date", "Date", ColumnType.DATE))
        add(ColumnDef("category", categoryHeader, ColumnType.CATEGORY, dropdownValues = categories.map { it.label }))
        add(ColumnDef("description", "Description", ColumnType.TEXT))
        add(ColumnDef("amount", "Amount (£)", ColumnType.MONEY))
    }

    override fun currentRows(userId: Int, taxYear: String): List<Map<String, Any?>> =
        loadEntries(userId, taxYear).map { e ->
            buildMap<String, Any?> {
                put("id", e.id)
                if (propertyType != null) put("property_id", e.propertyId)
                put("category", e.category)
                put("amount", e.amount)
                put("description", e.description)
                put("transaction_date", e.transactionDate)
            }
        }
}

// ---------------------------------------------------------------------------
// The sheets
// ---------------------------------------------------------------------------

/**
 * The UK dividends sheet covers [DividendCategory] only. Rows in the same
 * table with a [DividendScalarCategory] key belong to the special types
 * sheet ([IncomeDividendSpecialSheetSpec]).
 */
internal object IncomeDividendSheetSpec : EntrySheetSpec(
    ExportTable.INCOME_DIVIDENDS,
    null,
    DividendCategory.entries.map { CategoryOption(it.label, it.dbKey) },
    categoryHeader = "Type",
) {
    private val ukKeys = DividendCategory.entries.map { it.dbKey }.toSet()

    override fun loadEntries(userId: Int, taxYear: String): List<EntryRow> =
        IncomeDividendRepository.currentDividendsForYear(userId, taxYear)
            .filter { it.category in ukKeys }
            .map { EntryRow(it.id, null, it.category, it.amount, it.description, it.transactionDate) }

    override fun insert(userId: Int, taxYear: String, values: Map<String, Any?>) {
        IncomeDividendRepository.recordDividend(
            userId            = userId,
            taxYear           = taxYear,
            category          = values.textValue("category"),
            amount            = values.moneyValue("amount"),
            customerReference = null,
            description       = values.textValue("description"),
            transactionDate   = values.textValue("transaction_date"),
        )
    }

    override fun edit(existingId: Int, userId: Int, taxYear: String, values: Map<String, Any?>) {
        IncomeDividendRepository.edit(
            existingId        = existingId,
            userId            = userId,
            taxYear           = taxYear,
            category          = values.textValue("category"),
            amount            = values.moneyValue("amount"),
            customerReference = null,
            description       = values.textValue("description"),
            transactionDate   = values.textValue("transaction_date"),
        )
    }

    override fun delete(id: Int) {
        IncomeDividendRepository.delete(id)
    }
}

/**
 * UK dividends, special types: the [DividendScalarCategory] rows of the UK
 * dividends table. As in the GUI, they carry an optional customer reference
 * instead of a description, and the stored description is the reference (or
 * empty if there is none).
 */
internal object IncomeDividendSpecialSheetSpec : SheetSpec {
    override val table = ExportTable.INCOME_DIVIDENDS_SPECIAL
    override val propertyType: PropertyType? = null
    override val categories = DividendScalarCategory.entries.map { CategoryOption(it.label, it.dbKey) }
    override val comparedKeys = listOf("transaction_date", "category", "customer_reference", "amount")

    private val scalarKeys = DividendScalarCategory.entries.map { it.dbKey }.toSet()

    override fun columns(propertyDisplayStrings: List<String>): List<ColumnDef> = listOf(
        ColumnDef("id", "ID", ColumnType.INTEGER, readOnly = true),
        ColumnDef("transaction_date", "Date", ColumnType.DATE),
        ColumnDef("category", "Type", ColumnType.CATEGORY, dropdownValues = categories.map { it.label }),
        ColumnDef("customer_reference", "Customer reference", ColumnType.TEXT, required = false),
        ColumnDef("amount", "Gross amount (£)", ColumnType.MONEY),
    )

    override fun currentRows(userId: Int, taxYear: String): List<Map<String, Any?>> =
        IncomeDividendRepository.currentDividendsForYear(userId, taxYear)
            .filter { it.category in scalarKeys }
            .map {
                mapOf<String, Any?>(
                    "id"                 to it.id,
                    "category"           to it.category,
                    "customer_reference" to (it.customerReference ?: ""),
                    "amount"             to it.amount,
                    "transaction_date"   to it.transactionDate,
                )
            }

    override fun insert(userId: Int, taxYear: String, values: Map<String, Any?>) {
        val ref = values.textValue("customer_reference").ifBlank { null }
        IncomeDividendRepository.recordDividend(
            userId            = userId,
            taxYear           = taxYear,
            category          = values.textValue("category"),
            amount            = values.moneyValue("amount"),
            customerReference = ref,
            description       = ref ?: "",
            transactionDate   = values.textValue("transaction_date"),
        )
    }

    override fun edit(existingId: Int, userId: Int, taxYear: String, values: Map<String, Any?>) {
        val ref = values.textValue("customer_reference").ifBlank { null }
        IncomeDividendRepository.edit(
            existingId        = existingId,
            userId            = userId,
            taxYear           = taxYear,
            category          = values.textValue("category"),
            amount            = values.moneyValue("amount"),
            customerReference = ref,
            description       = ref ?: "",
            transactionDate   = values.textValue("transaction_date"),
        )
    }

    override fun delete(id: Int) {
        IncomeDividendRepository.delete(id)
    }
}

/** Foreign dividends: one row per country, with the GUI table's columns. */
internal object IncomeDividendForeignSheetSpec : SheetSpec {
    override val table = ExportTable.INCOME_DIVIDENDS_FOREIGN
    override val propertyType: PropertyType? = null
    override val categories = DividendForeignCategory.entries.map { CategoryOption(it.label, it.dbKey) }
    override val comparedKeys = listOf(
        "transaction_date", "category", "country_code", "amount_before_tax", "tax_taken_off",
        "special_withholding_tax", "foreign_tax_credit_relief", "taxable_amount",
    )

    override fun columns(propertyDisplayStrings: List<String>): List<ColumnDef> = listOf(
        ColumnDef("id", "ID", ColumnType.INTEGER, readOnly = true),
        ColumnDef("transaction_date", "Date", ColumnType.DATE),
        ColumnDef("category", "Type", ColumnType.CATEGORY, dropdownValues = categories.map { it.label }),
        ColumnDef("country_code", "Country", ColumnType.COUNTRY_CODE),
        ColumnDef("amount_before_tax", "Amt before tax (£)", ColumnType.MONEY, required = false),
        ColumnDef("tax_taken_off", "Tax taken off (£)", ColumnType.MONEY, required = false),
        ColumnDef("special_withholding_tax", "SWT (£)", ColumnType.MONEY, required = false),
        ColumnDef(
            "foreign_tax_credit_relief", "FTCR", ColumnType.BOOLEAN,
            required = false, dropdownValues = listOf("Yes", "No"),
        ),
        ColumnDef("taxable_amount", "Taxable (£)", ColumnType.MONEY),
    )

    override fun currentRows(userId: Int, taxYear: String): List<Map<String, Any?>> =
        IncomeDividendForeignRepository.currentEntriesForYear(userId, taxYear).map {
            mapOf<String, Any?>(
                "id"                        to it.id,
                "category"                  to it.category,
                "country_code"              to it.countryCode,
                "amount_before_tax"         to it.amountBeforeTax,
                "tax_taken_off"             to it.taxTakenOff,
                "special_withholding_tax"   to it.specialWithholdingTax,
                "foreign_tax_credit_relief" to it.foreignTaxCreditRelief,
                "taxable_amount"            to it.taxableAmount,
                "transaction_date"          to it.transactionDate,
            )
        }

    override fun insert(userId: Int, taxYear: String, values: Map<String, Any?>) {
        IncomeDividendForeignRepository.record(
            userId                 = userId,
            taxYear                = taxYear,
            category               = values.textValue("category"),
            countryCode            = values.textValue("country_code"),
            amountBeforeTax        = values.optionalMoney("amount_before_tax"),
            taxTakenOff            = values.optionalMoney("tax_taken_off"),
            specialWithholdingTax  = values.optionalMoney("special_withholding_tax"),
            foreignTaxCreditRelief = values.boolValue("foreign_tax_credit_relief"),
            taxableAmount          = values.moneyValue("taxable_amount"),
            transactionDate        = values.textValue("transaction_date"),
        )
    }

    override fun edit(existingId: Int, userId: Int, taxYear: String, values: Map<String, Any?>) {
        IncomeDividendForeignRepository.edit(
            existingId             = existingId,
            userId                 = userId,
            taxYear                = taxYear,
            category               = values.textValue("category"),
            countryCode            = values.textValue("country_code"),
            amountBeforeTax        = values.optionalMoney("amount_before_tax"),
            taxTakenOff            = values.optionalMoney("tax_taken_off"),
            specialWithholdingTax  = values.optionalMoney("special_withholding_tax"),
            foreignTaxCreditRelief = values.boolValue("foreign_tax_credit_relief"),
            taxableAmount          = values.moneyValue("taxable_amount"),
            transactionDate        = values.textValue("transaction_date"),
        )
    }

    override fun delete(id: Int) {
        IncomeDividendForeignRepository.delete(id)
    }
}

internal object IncomePropertyUkSheetSpec : EntrySheetSpec(
    ExportTable.INCOME_PROPERTY_UK,
    PropertyType.UK,
    IncomeCategory.entries.map { CategoryOption(it.label, it.dbKey) },
) {
    override fun loadEntries(userId: Int, taxYear: String): List<EntryRow> =
        IncomePropertyUkRepository.currentPropertyIncomeForYear(userId, taxYear).map {
            EntryRow(it.id, it.propertyId, it.category, it.amount, it.description, it.transactionDate)
        }

    override fun insert(userId: Int, taxYear: String, values: Map<String, Any?>) {
        val date = values.textValue("transaction_date")
        IncomePropertyUkRepository.recordPropertyIncome(
            periodId        = PeriodRepository.getOrCreateStandard(date).id,
            userId          = userId,
            propertyId      = values.intValue("property_id"),
            category        = values.textValue("category"),
            amount          = values.moneyValue("amount"),
            description     = values.textValue("description"),
            transactionDate = date,
        )
    }

    override fun edit(existingId: Int, userId: Int, taxYear: String, values: Map<String, Any?>) {
        val date = values.textValue("transaction_date")
        IncomePropertyUkRepository.edit(
            existingId      = existingId,
            periodId        = PeriodRepository.getOrCreateStandard(date).id,
            userId          = userId,
            propertyId      = values.intValue("property_id"),
            category        = values.textValue("category"),
            amount          = values.moneyValue("amount"),
            description     = values.textValue("description"),
            transactionDate = date,
        )
    }

    override fun delete(id: Int) {
        IncomePropertyUkRepository.delete(id)
    }
}

internal object IncomePropertyForeignSheetSpec : EntrySheetSpec(
    ExportTable.INCOME_PROPERTY_FOREIGN,
    PropertyType.FOREIGN,
    ForeignIncomeCategory.entries.map { CategoryOption(it.label, it.dbKey) },
) {
    override fun loadEntries(userId: Int, taxYear: String): List<EntryRow> =
        IncomePropertyForeignRepository.currentForeignPropertyIncomeForYear(userId, taxYear).map {
            EntryRow(it.id, it.propertyId, it.category, it.amount, it.description, it.transactionDate)
        }

    override fun insert(userId: Int, taxYear: String, values: Map<String, Any?>) {
        val date = values.textValue("transaction_date")
        IncomePropertyForeignRepository.recordForeignPropertyIncome(
            periodId        = PeriodRepository.getOrCreateStandard(date).id,
            userId          = userId,
            propertyId      = values.intValue("property_id"),
            category        = values.textValue("category"),
            amount          = values.moneyValue("amount"),
            description     = values.textValue("description"),
            transactionDate = date,
        )
    }

    override fun edit(existingId: Int, userId: Int, taxYear: String, values: Map<String, Any?>) {
        val date = values.textValue("transaction_date")
        IncomePropertyForeignRepository.edit(
            existingId      = existingId,
            periodId        = PeriodRepository.getOrCreateStandard(date).id,
            userId          = userId,
            propertyId      = values.intValue("property_id"),
            category        = values.textValue("category"),
            amount          = values.moneyValue("amount"),
            description     = values.textValue("description"),
            transactionDate = date,
        )
    }

    override fun delete(id: Int) {
        IncomePropertyForeignRepository.delete(id)
    }
}

internal object IncomeSavingsSheetSpec : EntrySheetSpec(
    ExportTable.INCOME_SAVINGS,
    null,
    SavingsCategory.entries.map { CategoryOption(it.label, it.dbKey) },
) {
    override fun loadEntries(userId: Int, taxYear: String): List<EntryRow> =
        IncomeSavingsRepository.currentForTaxYear(userId, taxYear).map {
            EntryRow(it.id, null, it.category, it.amount, it.description, it.transactionDate)
        }

    override fun insert(userId: Int, taxYear: String, values: Map<String, Any?>) {
        IncomeSavingsRepository.recordSavingsIncome(
            userId          = userId,
            taxYear         = taxYear,
            category        = values.textValue("category"),
            amount          = values.moneyValue("amount"),
            description     = values.textValue("description"),
            transactionDate = values.textValue("transaction_date"),
        )
    }

    override fun edit(existingId: Int, userId: Int, taxYear: String, values: Map<String, Any?>) {
        IncomeSavingsRepository.edit(
            existingId      = existingId,
            userId          = userId,
            taxYear         = taxYear,
            category        = values.textValue("category"),
            amount          = values.moneyValue("amount"),
            description     = values.textValue("description"),
            transactionDate = values.textValue("transaction_date"),
        )
    }

    override fun delete(id: Int) {
        IncomeSavingsRepository.delete(id)
    }
}

internal object ExpensePropertyUkSheetSpec : EntrySheetSpec(
    ExportTable.EXPENSES_PROPERTY_UK,
    PropertyType.UK,
    ExpenseCategory.entries.map { CategoryOption(it.label, it.dbKey) },
) {
    override fun loadEntries(userId: Int, taxYear: String): List<EntryRow> =
        ExpensePropertyUkRepository.currentPropertyExpensesForYear(userId, taxYear).map {
            EntryRow(it.id, it.propertyId, it.category, it.amount, it.description, it.transactionDate)
        }

    override fun insert(userId: Int, taxYear: String, values: Map<String, Any?>) {
        val date = values.textValue("transaction_date")
        ExpensePropertyUkRepository.record(
            periodId        = PeriodRepository.getOrCreateStandard(date).id,
            userId          = userId,
            propertyId      = values.intValue("property_id"),
            category        = values.textValue("category"),
            amount          = values.moneyValue("amount"),
            description     = values.textValue("description"),
            transactionDate = date,
        )
    }

    override fun edit(existingId: Int, userId: Int, taxYear: String, values: Map<String, Any?>) {
        val date = values.textValue("transaction_date")
        ExpensePropertyUkRepository.edit(
            existingId      = existingId,
            periodId        = PeriodRepository.getOrCreateStandard(date).id,
            userId          = userId,
            propertyId      = values.intValue("property_id"),
            category        = values.textValue("category"),
            amount          = values.moneyValue("amount"),
            description     = values.textValue("description"),
            transactionDate = date,
        )
    }

    override fun delete(id: Int) {
        ExpensePropertyUkRepository.delete(id)
    }
}

internal object ExpensePropertyForeignSheetSpec : EntrySheetSpec(
    ExportTable.EXPENSES_PROPERTY_FOREIGN,
    PropertyType.FOREIGN,
    ForeignExpenseCategory.entries.map { CategoryOption(it.label, it.dbKey) },
) {
    override fun loadEntries(userId: Int, taxYear: String): List<EntryRow> =
        ExpensePropertyForeignRepository.currentForeignPropertyExpensesForYear(userId, taxYear).map {
            EntryRow(it.id, it.propertyId, it.category, it.amount, it.description, it.transactionDate)
        }

    override fun insert(userId: Int, taxYear: String, values: Map<String, Any?>) {
        val date = values.textValue("transaction_date")
        ExpensePropertyForeignRepository.recordForeignPropertyExpense(
            periodId        = PeriodRepository.getOrCreateStandard(date).id,
            userId          = userId,
            propertyId      = values.intValue("property_id"),
            category        = values.textValue("category"),
            amount          = values.moneyValue("amount"),
            description     = values.textValue("description"),
            transactionDate = date,
        )
    }

    override fun edit(existingId: Int, userId: Int, taxYear: String, values: Map<String, Any?>) {
        val date = values.textValue("transaction_date")
        ExpensePropertyForeignRepository.edit(
            existingId      = existingId,
            periodId        = PeriodRepository.getOrCreateStandard(date).id,
            userId          = userId,
            propertyId      = values.intValue("property_id"),
            category        = values.textValue("category"),
            amount          = values.moneyValue("amount"),
            description     = values.textValue("description"),
            transactionDate = date,
        )
    }

    override fun delete(id: Int) {
        ExpensePropertyForeignRepository.delete(id)
    }
}

// ---------------------------------------------------------------------------
// Registry
// ---------------------------------------------------------------------------

/** Every sheet, in [ExportTable] order. */
val ALL_SHEET_SPECS: List<SheetSpec> = listOf(
    IncomeDividendSheetSpec,
    IncomeDividendSpecialSheetSpec,
    IncomeDividendForeignSheetSpec,
    IncomePropertyUkSheetSpec,
    IncomePropertyForeignSheetSpec,
    IncomeSavingsSheetSpec,
    ExpensePropertyUkSheetSpec,
    ExpensePropertyForeignSheetSpec,
)

fun sheetSpecFor(table: ExportTable): SheetSpec =
    ALL_SHEET_SPECS.first { it.table == table }