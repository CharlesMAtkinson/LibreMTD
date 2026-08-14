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

package org.charlesatkinson.libremtd.fileio

import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.charlesatkinson.libremtd.database.Period
import org.charlesatkinson.libremtd.database.PeriodRepository
import org.charlesatkinson.libremtd.database.PropertyRepository
import org.charlesatkinson.libremtd.database.tables.*
import org.charlesatkinson.libremtd.database.taxYearDateRange
import org.charlesatkinson.libremtd.ui.DividendCategory
import org.charlesatkinson.libremtd.ui.ExpenseCategory
import org.charlesatkinson.libremtd.ui.IncomeCategory
import org.charlesatkinson.libremtd.ui.SavingsCategory
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import kotlin.io.path.inputStream

// ---------------------------------------------------------------------------
// Public result types
// ---------------------------------------------------------------------------

data class ImportSummary(
    val inserted:   Int,
    val superseded: Int,
    val unchanged:  Int,
    val deleted:    Int,
    val warnings:   List<String>,
)

sealed class ImportResult {
    data class Success(val summary: ImportSummary) : ImportResult()
    data class Failure(val errors:  List<String>)  : ImportResult()
    /**
     * Conflicts found — caller must present them to the user and call
     * [proceed] if the user chooses to continue.
     */
    data class ConflictsFound(
        val conflicts: List<ConflictRow>,
        val proceed:   () -> ImportResult,
    ) : ImportResult()
}

/**
 * One spreadsheet row that matches an existing database record on all
 * data fields even though the row carries no id value.
 */
data class ConflictRow(
    val sheetRowNum:     Int,
    val existingDbId:    Int,
    val category:        String,
    val amount:          Double,
    val description:     String,
    val transactionDate: String,
    val propertyDisplay: String?,   // non-null for INCOME_PROPERTY only
)

// ---------------------------------------------------------------------------
// SpreadsheetImporter
// ---------------------------------------------------------------------------

object SpreadsheetImporter {

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Imports from [file], inferring which table(s) to import by inspecting
     * which known sheet names are present in the workbook.
     *
     * If exactly one known sheet is present, that table is imported.
     * If all four known sheets are present, all tables are imported.
     * Any other combination is an error.
     *
     * The table selector is therefore not needed in the UI.
     */
    fun import(
        file:    Path,
        userId:  Int,
        taxYear: String,
    ): ImportResult {
        val wb = openWorkbook(file) ?: return ImportResult.Failure(
            listOf("Cannot open file.  Is it a valid .xlsx spreadsheet?")
        )

        val presentTables = ExportTable.entries.filter { wb.getSheet(it.label) != null }

        return when {
            presentTables.isEmpty() ->
                ImportResult.Failure(
                    listOf(
                        "No recognised sheet found.  Expected one or more of: " +
                                ExportTable.entries.joinToString { "\"${it.label}\"" } + "."
                    )
                )

            presentTables.size == ExportTable.entries.size ->
                importAll(wb, userId, taxYear)

            presentTables.size == 1 ->
                importSingle(wb, presentTables.single(), userId, taxYear)

            else ->
                ImportResult.Failure(
                    listOf(
                        "Found ${presentTables.size} recognised sheets " +
                                "(${presentTables.joinToString { "\"${it.label}\"" }}).  " +
                                "The file must contain either one sheet or all four."
                    )
                )
        }
    }

    // ------------------------------------------------------------------
    // Internal single-table import
    // ------------------------------------------------------------------

    private fun importSingle(
        wb:      XSSFWorkbook,
        table:   ExportTable,
        userId:  Int,
        taxYear: String,
    ): ImportResult {
        val sheet = wb.getSheet(table.label)!! // presence already verified by caller

        val errors         = mutableListOf<String>()
        val propertyLookup = if (table == ExportTable.INCOME_PROPERTY)
            buildPropertyLookup(userId) else emptyMap()
        val cols           = resolvedColumnsFor(table, propertyLookup)

        validateHeaders(sheet, cols, errors)
        if (errors.isNotEmpty()) return ImportResult.Failure(errors)

        val rows = parseRows(sheet, cols, table, taxYear, propertyLookup, errors)
        if (errors.isNotEmpty()) return ImportResult.Failure(errors)

        validateIdRows(rows, table, userId, errors)
        if (errors.isNotEmpty()) return ImportResult.Failure(errors)

        val conflicts = findConflicts(rows, table, userId)
        if (conflicts.isNotEmpty()) {
            return ImportResult.ConflictsFound(conflicts) {
                val warnings = mutableListOf<String>()
                val periods  = periodsForTable(table, taxYear)
                transaction { persist(rows, table, userId, taxYear, periods, warnings) }
            }
        }

        val warnings = mutableListOf<String>()
        val periods  = periodsForTable(table, taxYear)
        return transaction { persist(rows, table, userId, taxYear, periods, warnings) }
    }

    // ------------------------------------------------------------------
    // Internal all-tables import
    // ------------------------------------------------------------------

    private fun importAll(
        wb:      XSSFWorkbook,
        userId:  Int,
        taxYear: String,
    ): ImportResult {
        val errors         = mutableListOf<String>()
        val propertyLookup = buildPropertyLookup(userId)

        data class ParsedTable(val table: ExportTable, val rows: List<ParsedRow>)
        val parsed = mutableListOf<ParsedTable>()

        for (table in ExportTable.entries) {
            val sheet  = wb.getSheet(table.label)!! // presence already verified by caller
            val lookup = if (table == ExportTable.INCOME_PROPERTY) propertyLookup else emptyMap()
            val cols   = resolvedColumnsFor(table, lookup)

            validateHeaders(sheet, cols, errors)
            if (errors.isNotEmpty()) return ImportResult.Failure(errors)

            val rows = parseRows(sheet, cols, table, taxYear, lookup, errors)
            if (errors.isNotEmpty()) return ImportResult.Failure(errors)

            validateIdRows(rows, table, userId, errors)
            if (errors.isNotEmpty()) return ImportResult.Failure(errors)

            parsed += ParsedTable(table, rows)
        }

        if (errors.isNotEmpty()) return ImportResult.Failure(errors)

        val allConflicts = parsed.flatMap { (table, rows) -> findConflicts(rows, table, userId) }

        fun doImport(): ImportResult {
            val warnings = mutableListOf<String>()
            var totalInserted = 0; var totalSuperseded = 0
            var totalUnchanged = 0; var totalDeleted = 0
            val result = transaction {
                for ((table, rows) in parsed) {
                    val periods = periodsForTable(table, taxYear)
                    val r = persist(rows, table, userId, taxYear, periods, warnings)
                    when (r) {
                        is ImportResult.Success -> {
                            totalInserted   += r.summary.inserted
                            totalSuperseded += r.summary.superseded
                            totalUnchanged  += r.summary.unchanged
                            totalDeleted    += r.summary.deleted
                        }
                        is ImportResult.Failure        -> return@transaction r
                        is ImportResult.ConflictsFound -> return@transaction r   // cannot happen here
                    }
                }
                ImportResult.Success(
                    ImportSummary(totalInserted, totalSuperseded, totalUnchanged, totalDeleted, warnings)
                )
            }
            return result
        }

        return if (allConflicts.isNotEmpty())
            ImportResult.ConflictsFound(allConflicts) { doImport() }
        else
            doImport()
    }

    // ------------------------------------------------------------------
    // Period resolution
    // ------------------------------------------------------------------

    /**
     * Returns the HMRC-defined periods for [taxYear] for tables that use
     * them, or an empty list for tables that do not (dividends, savings).
     * Periods are not per-user.
     */
    private fun periodsForTable(table: ExportTable, taxYear: String): List<Period> =
        when (table) {
            ExportTable.INCOME_PROPERTY,
            ExportTable.EXPENSES          -> PeriodRepository.findByTaxYear(taxYear)
            ExportTable.INCOME_DIVIDENDS,
            ExportTable.INCOME_SAVINGS    -> emptyList()
        }

    /**
     * Finds the period whose startDate..endDate range contains
     * [transactionDate].  Returns null if no period covers the date.
     */
    private fun periodForDate(periods: List<Period>, transactionDate: String): Period? {
        val date = LocalDate.parse(transactionDate)
        return periods.firstOrNull { p ->
            val start = LocalDate.parse(p.startDate)
            val end   = LocalDate.parse(p.endDate)
            date >= start && date <= end
        }
    }

    // ------------------------------------------------------------------
    // Property lookup
    // ------------------------------------------------------------------

    private fun buildPropertyLookup(userId: Int): Map<String, Int> {
        val properties = PropertyRepository.findByUser(userId)
        return buildMap {
            for (p in properties) {
                put(propertyDisplayString(p.address, p.postcode, p.countryCode), p.id)
                put(p.id.toString(), p.id)
            }
        }
    }

    // ------------------------------------------------------------------
    // Column resolution
    // ------------------------------------------------------------------

    private fun resolvedColumnsFor(
        table:          ExportTable,
        propertyLookup: Map<String, Int>,
    ): List<ColumnDef> = when (table) {
        ExportTable.INCOME_PROPERTY -> {
            val displayStrings = propertyLookup.keys
                .filter { it.toIntOrNull() == null }
                .sorted()
            incomePropertyColumns(displayStrings)
        }
        else -> SpreadsheetExporter.staticColumnsFor(table)
    }

    // ------------------------------------------------------------------
    // Workbook open
    // ------------------------------------------------------------------

    private fun openWorkbook(file: Path): XSSFWorkbook? = try {
        XSSFWorkbook(file.inputStream())
    } catch (_: Exception) { null }

    // ------------------------------------------------------------------
    // Header validation
    // ------------------------------------------------------------------

    private fun validateHeaders(
        sheet:  Sheet,
        cols:   List<ColumnDef>,
        errors: MutableList<String>,
    ) {
        val dbNameRow = sheet.getRow(1)
        if (dbNameRow == null) {
            errors += "Sheet \"${sheet.sheetName}\": missing header rows."
            return
        }
        cols.forEachIndexed { ci, col ->
            val actual = cellString(dbNameRow, ci)
            if (actual != col.dbName) {
                errors += "Sheet \"${sheet.sheetName}\": column ${ci + 1} " +
                        "expected db name \"${col.dbName}\", found \"$actual\".  " +
                        "Do not add, remove, or reorder columns."
            }
        }
    }

    // ------------------------------------------------------------------
    // Row parsing
    // ------------------------------------------------------------------

    /**
     * Represents one parsed data row from the spreadsheet.
     *
     * [category], [amount], [description], and [transactionDate] are
     * meaningful only when [isDelete] is false.  Delete rows carry only
     * [id] and [sheetRowNum]; all other fields hold their zero/empty
     * defaults and must not be used.
     *
     * [category] is stored as a dbKey — the label-to-dbKey translation is
     * performed inside [parseRows] immediately after the category cell is read.
     *
     * A delete row is signalled by [id] being non-null and all non-hidden
     * data cells being blank.  On import the identified record is superseded
     * and no replacement is inserted.
     */
    private data class ParsedRow(
        val sheetRowNum:     Int,
        val id:              Int?,
        val isDelete:        Boolean,
        val propertyId:      Int?,
        val propertyDisplay: String?,
        val category:        String,    // dbKey; blank for delete rows
        val amount:          Double,    // 0.0 for delete rows
        val description:     String,    // blank for delete rows
        val transactionDate: String,    // blank for delete rows
    )

    private fun parseRows(
        sheet:          Sheet,
        cols:           List<ColumnDef>,
        table:          ExportTable,
        taxYear:        String,
        propertyLookup: Map<String, Int>,
        errors:         MutableList<String>,
    ): List<ParsedRow> {
        val validCategories      = validCategoriesFor(table)
        val result               = mutableListOf<ParsedRow>()
        val (startDate, endDate) = taxYearDateRange(taxYear)
        val start                = LocalDate.parse(startDate)
        val end                  = LocalDate.parse(endDate)

        // Indices of columns that are hidden in the spreadsheet.  These are
        // locked and cannot be blanked by the user, so they must be excluded
        // from the blank check used to detect delete rows.
        val hiddenColIndices = cols.indices.filter { cols[it].hidden }.toSet()

        for (rowIdx in 2..sheet.lastRowNum) {
            val row = sheet.getRow(rowIdx) ?: continue
            if (isBlankRow(row, cols.size)) continue   // completely empty — skip silently

            val rowNum    = rowIdx + 1
            val rowErrors = mutableListOf<String>()
            fun err(msg: String) { rowErrors += "Sheet \"${sheet.sheetName}\" row $rowNum: $msg" }

            // ── id ──────────────────────────────────────────────────────────
            val idRaw = cellString(row, colIndex(cols, "id"))
            val id: Int? = when {
                idRaw.isBlank() -> null
                else            -> idRaw.toIntOrNull()
                    ?: run { err("id \"$idRaw\" is not a valid integer."); null }
            }

            // ── delete row detection ─────────────────────────────────────────
            // A row is a delete instruction when id is present and every
            // non-hidden column other than id is blank.  Hidden columns
            // (e.g. property_id) are excluded because the user cannot blank
            // them — they are locked by sheet protection.
            // The id column itself (dbName "id") is always excluded since it
            // must be present for a delete row to be valid.
            val idColIndex   = colIndex(cols, "id")
            val excludedCols = hiddenColIndices + idColIndex
            val isDelete     = id != null && isBlankRow(row, cols.size, excludeCols = excludedCols)

            if (isDelete) {
                // No further field validation — only the id is needed, and it
                // has already been parsed above.  Any id parse error is
                // already in rowErrors.
                errors += rowErrors
                if (rowErrors.isEmpty()) {
                    result += ParsedRow(
                        sheetRowNum     = rowNum,
                        id              = id,
                        isDelete        = true,
                        propertyId      = null,
                        propertyDisplay = null,
                        category        = "",
                        amount          = 0.0,
                        description     = "",
                        transactionDate = "",
                    )
                }
                continue
            }

            // ── property (INCOME_PROPERTY only) ─────────────────────────────
            var resolvedPropertyId:      Int?    = null
            var resolvedPropertyDisplay: String? = null
            if (table == ExportTable.INCOME_PROPERTY) {
                val pidRaw  = cellString(row, colIndex(cols, "property_id"))
                val propRaw = cellString(row, colIndex(cols, "property"))

                resolvedPropertyId = when {
                    pidRaw.isNotBlank() -> {
                        val pid = pidRaw.toIntOrNull()
                            ?: run { err("property_id \"$pidRaw\" is not a valid integer."); null }
                        if (pid != null && propertyLookup[pid.toString()] == null) {
                            err("property_id $pid does not match any current property.")
                            null
                        } else pid
                    }
                    propRaw.isNotBlank() -> {
                        propertyLookup[propRaw]
                            ?: run {
                                err(
                                    "Property \"$propRaw\" not found.  " +
                                            "It may have been deleted.  " +
                                            "Available: ${
                                                propertyLookup.keys
                                                    .filter { it.toIntOrNull() == null }
                                                    .sorted()
                                                    .joinToString()
                                            }"
                                )
                                null
                            }
                    }
                    else -> { err("property must not be blank."); null }
                }
                resolvedPropertyDisplay = propRaw.ifBlank {
                    propertyLookup.entries
                        .firstOrNull { it.value == resolvedPropertyId && it.key.toIntOrNull() == null }
                        ?.key
                }
            }

            // ── category ────────────────────────────────────────────────────
            val categoryLabel = cellString(row, colIndex(cols, "category"))
            when {
                categoryLabel.isBlank()           -> err("category must not be blank.")
                categoryLabel !in validCategories -> err(
                    "category \"$categoryLabel\" is not valid.  " +
                            "Valid values: ${validCategories.sorted().joinToString()}"
                )
            }
            val categoryDbKey = if (categoryLabel.isNotBlank() && categoryLabel in validCategories)
                labelToDbKey(table, categoryLabel)
            else
                categoryLabel   // validation already failed; row won't be persisted

            // ── amount ──────────────────────────────────────────────────────
            val amountRaw = cellString(row, colIndex(cols, "amount")).replace(",", "")
            val amount    = amountRaw.toDoubleOrNull()
            when {
                amountRaw.isBlank() -> err("amount must not be blank.")
                amount == null      -> err("amount \"$amountRaw\" is not a valid number.")
                amount <= 0         -> err("amount must be greater than zero.")
            }

            // ── description ─────────────────────────────────────────────────
            val description = cellString(row, colIndex(cols, "description"))
            if (description.isBlank()) err("description must not be blank.")

            // ── transaction_date ────────────────────────────────────────────
            val dateText   = cellString(row, colIndex(cols, "transaction_date"))
            val parsedDate: LocalDate? = when {
                dateText.isBlank()     -> { err("Transaction Date must not be blank."); null }
                !isValidDate(dateText) -> { err("Transaction Date \"$dateText\" must be YYYY-MM-DD."); null }
                else                   -> LocalDate.parse(dateText)
            }

            if (parsedDate != null && (parsedDate < start || parsedDate > end)) {
                err(
                    "Transaction Date \"$dateText\" is outside tax year $taxYear " +
                            "($startDate to $endDate).  " +
                            "Check you have selected the correct tax year."
                )
            }

            errors += rowErrors
            if (rowErrors.isEmpty()) {
                result += ParsedRow(
                    sheetRowNum     = rowNum,
                    id              = id,
                    isDelete        = false,
                    propertyId      = resolvedPropertyId,
                    propertyDisplay = resolvedPropertyDisplay,
                    category        = categoryDbKey,
                    amount          = amount!!,
                    description     = description,
                    transactionDate = dateText,
                )
            }
        }

        return result
    }

    // ------------------------------------------------------------------
    // Category helpers
    // ------------------------------------------------------------------

    private fun validCategoriesFor(table: ExportTable): Set<String> = when (table) {
        ExportTable.EXPENSES         -> ExpenseCategory.entries.map  { it.label }.toSet()
        ExportTable.INCOME_DIVIDENDS -> DividendCategory.entries.map { it.label }.toSet()
        ExportTable.INCOME_PROPERTY  -> IncomeCategory.entries.map   { it.label }.toSet()
        ExportTable.INCOME_SAVINGS   -> SavingsCategory.entries.map  { it.label }.toSet()
    }

    private fun labelToDbKey(table: ExportTable, label: String): String = when (table) {
        ExportTable.EXPENSES         -> ExpenseCategory.entries.firstOrNull  { it.label == label }?.dbKey ?: label
        ExportTable.INCOME_DIVIDENDS -> DividendCategory.entries.firstOrNull { it.label == label }?.dbKey ?: label
        ExportTable.INCOME_PROPERTY  -> IncomeCategory.entries.firstOrNull   { it.label == label }?.dbKey ?: label
        ExportTable.INCOME_SAVINGS   -> SavingsCategory.entries.firstOrNull  { it.label == label }?.dbKey ?: label
    }

    // ------------------------------------------------------------------
    // ID validation
    // ------------------------------------------------------------------

    /**
     * Checks that every row carrying an id (both modify and delete rows)
     * refers to a live (non-superseded) record belonging to [userId].
     */
    private fun validateIdRows(
        rows:   List<ParsedRow>,
        table:  ExportTable,
        userId: Int,
        errors: MutableList<String>,
    ) {
        val idsToCheck = rows.mapNotNull { it.id }.toSet()
        if (idsToCheck.isEmpty()) return

        val liveIds: Set<Int> = transaction {
            when (table) {
                ExportTable.EXPENSES ->
                    ExpensePropertyUkEntries
                        .select(ExpensePropertyUkEntries.id)
                        .where {
                            (ExpensePropertyUkEntries.userId eq userId) and
                                    ExpensePropertyUkEntries.supersededAt.isNull()
                        }
                        .map { it[ExpensePropertyUkEntries.id] }.toSet()

                ExportTable.INCOME_DIVIDENDS ->
                    IncomeDividendEntries
                        .select(IncomeDividendEntries.id)
                        .where {
                            (IncomeDividendEntries.userId eq userId) and
                                    IncomeDividendEntries.supersededAt.isNull()
                        }
                        .map { it[IncomeDividendEntries.id] }.toSet()

                ExportTable.INCOME_PROPERTY ->
                    IncomePropertyUkEntries
                        .select(IncomePropertyUkEntries.id)
                        .where {
                            (IncomePropertyUkEntries.userId eq userId) and
                                    IncomePropertyUkEntries.supersededAt.isNull()
                        }
                        .map { it[IncomePropertyUkEntries.id] }.toSet()

                ExportTable.INCOME_SAVINGS ->
                    IncomeSavingsEntries
                        .select(IncomeSavingsEntries.id)
                        .where {
                            (IncomeSavingsEntries.userId eq userId) and
                                    IncomeSavingsEntries.supersededAt.isNull()
                        }
                        .map { it[IncomeSavingsEntries.id] }.toSet()
            }
        }

        for (row in rows) {
            if (row.id != null && row.id !in liveIds)
                errors += "Sheet row ${row.sheetRowNum}: id ${row.id} does not exist " +
                        "or has already been superseded.  The file cannot be imported."
        }
    }

    // ------------------------------------------------------------------
    // Conflict detection
    // ------------------------------------------------------------------

    /**
     * Finds new rows (no id, not delete) that appear to duplicate an
     * existing live record on all data fields.  Delete rows are excluded —
     * they target a specific id and cannot conflict.
     */
    private fun findConflicts(
        rows:   List<ParsedRow>,
        table:  ExportTable,
        userId: Int,
    ): List<ConflictRow> {
        val newRows = rows.filter { it.id == null && !it.isDelete }
        if (newRows.isEmpty()) return emptyList()

        return transaction {
            newRows.mapNotNull { row ->
                val existingId: Int? = when (table) {

                    ExportTable.EXPENSES ->
                        ExpensePropertyUkEntries
                            .select(ExpensePropertyUkEntries.id)
                            .where {
                                (ExpensePropertyUkEntries.userId          eq userId)              and
                                        (ExpensePropertyUkEntries.category        eq row.category)        and
                                        (ExpensePropertyUkEntries.amount          eq row.amount)          and
                                        (ExpensePropertyUkEntries.description     eq row.description)     and
                                        (ExpensePropertyUkEntries.transactionDate eq row.transactionDate) and
                                        ExpensePropertyUkEntries.supersededAt.isNull()
                            }
                            .firstOrNull()?.get(ExpensePropertyUkEntries.id)

                    ExportTable.INCOME_DIVIDENDS ->
                        IncomeDividendEntries
                            .select(IncomeDividendEntries.id)
                            .where {
                                (IncomeDividendEntries.userId          eq userId)              and
                                        (IncomeDividendEntries.category        eq row.category)        and
                                        (IncomeDividendEntries.amount          eq row.amount)          and
                                        (IncomeDividendEntries.description     eq row.description)     and
                                        (IncomeDividendEntries.transactionDate eq row.transactionDate) and
                                        IncomeDividendEntries.supersededAt.isNull()
                            }
                            .firstOrNull()?.get(IncomeDividendEntries.id)

                    ExportTable.INCOME_PROPERTY ->
                        IncomePropertyUkEntries
                            .select(IncomePropertyUkEntries.id)
                            .where {
                                (IncomePropertyUkEntries.userId          eq userId)              and
                                        (IncomePropertyUkEntries.propertyId      eq row.propertyId!!)    and
                                        (IncomePropertyUkEntries.category        eq row.category)        and
                                        (IncomePropertyUkEntries.amount          eq row.amount)          and
                                        (IncomePropertyUkEntries.description     eq row.description)     and
                                        (IncomePropertyUkEntries.transactionDate eq row.transactionDate) and
                                        IncomePropertyUkEntries.supersededAt.isNull()
                            }
                            .firstOrNull()?.get(IncomePropertyUkEntries.id)

                    ExportTable.INCOME_SAVINGS ->
                        IncomeSavingsEntries
                            .select(IncomeSavingsEntries.id)
                            .where {
                                (IncomeSavingsEntries.userId          eq userId)              and
                                        (IncomeSavingsEntries.category        eq row.category)        and
                                        (IncomeSavingsEntries.amount          eq row.amount)          and
                                        (IncomeSavingsEntries.description     eq row.description)     and
                                        (IncomeSavingsEntries.transactionDate eq row.transactionDate) and
                                        IncomeSavingsEntries.supersededAt.isNull()
                            }
                            .firstOrNull()?.get(IncomeSavingsEntries.id)
                }

                existingId?.let {
                    ConflictRow(
                        sheetRowNum     = row.sheetRowNum,
                        existingDbId    = it,
                        category        = row.category,
                        amount          = row.amount,
                        description     = row.description,
                        transactionDate = row.transactionDate,
                        propertyDisplay = row.propertyDisplay,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /**
     * Persists all [rows] for [table].  Must be called inside an Exposed
     * transaction block.
     *
     * Row handling:
     * - isDelete = true:  supersede the existing record; no new insert.
     * - id non-null, isDelete = false:  if changed, supersede and re-insert;
     *   otherwise count as unchanged.
     * - id null:  insert as a new record.
     *
     * For INCOME_PROPERTY and EXPENSES, period_id is resolved per non-delete
     * row by matching the transaction date against HMRC period ranges.  If no
     * period covers a row's date, an ImportResult.Failure is returned.
     * Delete rows do not require period resolution.
     */
    private fun persist(
        rows:     List<ParsedRow>,
        table:    ExportTable,
        userId:   Int,
        taxYear:  String,
        periods:  List<Period>,
        warnings: MutableList<String>,
    ): ImportResult {
        var inserted = 0; var superseded = 0; var unchanged = 0; var deleted = 0
        val now = LocalDateTime.now().toString()

        for (row in rows) {

            // ── Delete ───────────────────────────────────────────────────────
            if (row.isDelete) {
                supersedeRow(table, row.id!!, now)
                deleted++
                continue
            }

            // ── Resolve period_id for tables that require it ──────────────────
            val periodId: Int? = when (table) {
                ExportTable.INCOME_PROPERTY,
                ExportTable.EXPENSES -> {
                    val period = periodForDate(periods, row.transactionDate)
                    if (period == null) {
                        return ImportResult.Failure(
                            listOf(
                                "Sheet row ${row.sheetRowNum}: Transaction Date " +
                                        "\"${row.transactionDate}\" does not fall within any " +
                                        "known HMRC period for tax year $taxYear.  " +
                                        "Fetch obligations from HMRC before importing."
                            )
                        )
                    }
                    period.id
                }
                ExportTable.INCOME_DIVIDENDS,
                ExportTable.INCOME_SAVINGS -> null
            }

            // ── Insert or modify ─────────────────────────────────────────────
            if (row.id == null) {
                insertRow(table, row, userId, taxYear, periodId, now)
                inserted++
            } else {
                if (hasChanged(table, row)) {
                    supersedeRow(table, row.id, now)
                    insertRow(table, row, userId, taxYear, periodId, now)
                    superseded++
                    inserted++
                } else {
                    unchanged++
                }
            }
        }

        return ImportResult.Success(ImportSummary(inserted, superseded, unchanged, deleted, warnings))
    }

    private fun hasChanged(table: ExportTable, row: ParsedRow): Boolean = when (table) {
        ExportTable.EXPENSES ->
            ExpensePropertyUkEntries.selectAll().where { ExpensePropertyUkEntries.id eq row.id!! }.single().let { db ->
                db[ExpensePropertyUkEntries.category]        != row.category        ||
                        db[ExpensePropertyUkEntries.amount]          != row.amount          ||
                        db[ExpensePropertyUkEntries.description]     != row.description     ||
                        db[ExpensePropertyUkEntries.transactionDate] != row.transactionDate
            }

        ExportTable.INCOME_DIVIDENDS ->
            IncomeDividendEntries.selectAll().where { IncomeDividendEntries.id eq row.id!! }.single().let { db ->
                db[IncomeDividendEntries.category]        != row.category        ||
                        db[IncomeDividendEntries.amount]          != row.amount          ||
                        db[IncomeDividendEntries.description]     != row.description     ||
                        db[IncomeDividendEntries.transactionDate] != row.transactionDate
            }

        ExportTable.INCOME_PROPERTY ->
            IncomePropertyUkEntries.selectAll().where { IncomePropertyUkEntries.id eq row.id!! }.single().let { db ->
                db[IncomePropertyUkEntries.propertyId]      != row.propertyId      ||
                        db[IncomePropertyUkEntries.category]        != row.category        ||
                        db[IncomePropertyUkEntries.amount]          != row.amount          ||
                        db[IncomePropertyUkEntries.description]     != row.description     ||
                        db[IncomePropertyUkEntries.transactionDate] != row.transactionDate
            }

        ExportTable.INCOME_SAVINGS ->
            IncomeSavingsEntries.selectAll().where { IncomeSavingsEntries.id eq row.id!! }.single().let { db ->
                db[IncomeSavingsEntries.category]        != row.category        ||
                        db[IncomeSavingsEntries.amount]          != row.amount          ||
                        db[IncomeSavingsEntries.description]     != row.description     ||
                        db[IncomeSavingsEntries.transactionDate] != row.transactionDate
            }
    }

    private fun supersedeRow(table: ExportTable, id: Int, now: String) {
        when (table) {
            ExportTable.EXPENSES ->
                ExpensePropertyUkEntries.update({ ExpensePropertyUkEntries.id eq id }) { it[supersededAt] = now }
            ExportTable.INCOME_DIVIDENDS ->
                IncomeDividendEntries.update({ IncomeDividendEntries.id eq id }) { it[supersededAt] = now }
            ExportTable.INCOME_PROPERTY ->
                IncomePropertyUkEntries.update({ IncomePropertyUkEntries.id eq id }) { it[supersededAt] = now }
            ExportTable.INCOME_SAVINGS ->
                IncomeSavingsEntries.update({ IncomeSavingsEntries.id eq id }) { it[supersededAt] = now }
        }
    }

    /**
     * Inserts one row.  [periodId] is non-null for INCOME_PROPERTY and
     * EXPENSES, and null for INCOME_DIVIDENDS and INCOME_SAVINGS.
     * Must be called inside an Exposed transaction block.
     */
    private fun insertRow(
        table:    ExportTable,
        row:      ParsedRow,
        userId:   Int,
        taxYear:  String,
        periodId: Int?,
        now:      String,
    ) {
        when (table) {
            ExportTable.EXPENSES ->
                ExpensePropertyUkEntries.insert {
                    it[ExpensePropertyUkEntries.userId]          = userId
                    it[ExpensePropertyUkEntries.periodId]        = periodId!!
                    it[ExpensePropertyUkEntries.category]        = row.category
                    it[ExpensePropertyUkEntries.amount]          = row.amount
                    it[ExpensePropertyUkEntries.description]     = row.description
                    it[ExpensePropertyUkEntries.transactionDate] = row.transactionDate
                    it[ExpensePropertyUkEntries.recordedAt]      = now
                    it[ExpensePropertyUkEntries.supersededAt]    = null
                }
            ExportTable.INCOME_DIVIDENDS ->
                IncomeDividendEntries.insert {
                    it[IncomeDividendEntries.userId]          = userId
                    it[IncomeDividendEntries.taxYear]         = taxYear
                    it[IncomeDividendEntries.category]        = row.category
                    it[IncomeDividendEntries.amount]          = row.amount
                    it[IncomeDividendEntries.description]     = row.description
                    it[IncomeDividendEntries.transactionDate] = row.transactionDate
                    it[IncomeDividendEntries.recordedAt]      = now
                    it[IncomeDividendEntries.supersededAt]    = null
                }
            ExportTable.INCOME_PROPERTY ->
                IncomePropertyUkEntries.insert {
                    it[IncomePropertyUkEntries.userId]          = userId
                    it[IncomePropertyUkEntries.periodId]        = periodId!!
                    it[IncomePropertyUkEntries.propertyId]      = row.propertyId!!
                    it[IncomePropertyUkEntries.category]        = row.category
                    it[IncomePropertyUkEntries.amount]          = row.amount
                    it[IncomePropertyUkEntries.description]     = row.description
                    it[IncomePropertyUkEntries.transactionDate] = row.transactionDate
                    it[IncomePropertyUkEntries.recordedAt]      = now
                    it[IncomePropertyUkEntries.supersededAt]    = null
                }
            ExportTable.INCOME_SAVINGS ->
                IncomeSavingsEntries.insert {
                    it[IncomeSavingsEntries.userId]          = userId
                    it[IncomeSavingsEntries.taxYear]         = taxYear
                    it[IncomeSavingsEntries.category]        = row.category
                    it[IncomeSavingsEntries.amount]          = row.amount
                    it[IncomeSavingsEntries.description]     = row.description
                    it[IncomeSavingsEntries.transactionDate] = row.transactionDate
                    it[IncomeSavingsEntries.recordedAt]      = now
                    it[IncomeSavingsEntries.supersededAt]    = null
                }
        }
    }

    // ------------------------------------------------------------------
    // Cell / row helpers
    // ------------------------------------------------------------------

    private fun colIndex(cols: List<ColumnDef>, dbName: String): Int =
        cols.indexOfFirst { it.dbName == dbName }
            .also { check(it >= 0) { "Column \"$dbName\" not found in column definitions" } }

    /**
     * Returns the cell content as a plain string.
     *
     * Numeric cells with a date format are converted to YYYY-MM-DD.
     * Non-date numeric cells are rendered as an integer if whole, otherwise
     * as a plain decimal string.
     */
    private fun cellString(row: Row, col: Int): String {
        val cell = row.getCell(col) ?: return ""
        return when (cell.cellType) {
            CellType.NUMERIC ->
                if (DateUtil.isCellDateFormatted(cell)) {
                    DateUtil.getLocalDateTime(cell.numericCellValue)
                        .toLocalDate()
                        .toString()
                } else {
                    val d = cell.numericCellValue
                    if (d == kotlin.math.floor(d)) d.toLong().toString()
                    else d.toBigDecimal().toPlainString()
                }
            CellType.BLANK -> ""
            else           -> cell.stringCellValue.trim()
        }
    }

    /**
     * Returns true if every cell in columns 0..[cols]-1 is blank,
     * optionally skipping the column indices in [excludeCols].
     *
     * Only [CellType.BLANK] and empty [CellType.STRING] cells are considered
     * blank.  Numeric cells (including locked id/property_id columns) are not
     * blank; callers must exclude those columns via [excludeCols] when
     * checking whether a row is a user-blanked delete row.
     */
    private fun isBlankRow(row: Row, cols: Int, excludeCols: Set<Int> = emptySet()): Boolean =
        (0 until cols).filter { it !in excludeCols }.all { ci ->
            val cell = row.getCell(ci)
            cell == null ||
                    cell.cellType == CellType.BLANK ||
                    (cell.cellType == CellType.STRING && cell.stringCellValue.isBlank())
        }

    private fun isValidDate(text: String): Boolean = try {
        LocalDate.parse(text); true
    } catch (_: DateTimeParseException) { false }
}
