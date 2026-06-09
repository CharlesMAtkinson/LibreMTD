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
import org.apache.poi.ss.util.CellRangeAddressList
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.charlesatkinson.libremtd.database.tables.ExpenseEntries
import org.charlesatkinson.libremtd.database.tables.IncomeDividendEntries
import org.charlesatkinson.libremtd.database.tables.IncomePropertyEntries
import org.charlesatkinson.libremtd.database.tables.IncomeSavingsEntries
import org.charlesatkinson.libremtd.database.taxYearDateRange
import org.charlesatkinson.libremtd.ui.DividendCategory
import org.charlesatkinson.libremtd.ui.ExpenseCategory
import org.charlesatkinson.libremtd.ui.IncomeCategory
import org.charlesatkinson.libremtd.ui.SavingsCategory
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.FileOutputStream
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------------------
// Domain model
// ---------------------------------------------------------------------------

enum class ExportTable(val label: String, val fileName: String) {
    INCOME_PROPERTY("Income (property)", "income_property"),
    INCOME_DIVIDENDS("Income (dividends)", "income_dividends"),
    EXPENSES("Expenses", "expenses"),
    INCOME_SAVINGS("Income (savings)", "income_savings"),
}

/**
 * Describes one column in an exported sheet.
 *
 * [hidden] columns are written to the sheet so they survive a round-trip
 * import, but are hidden via POI's setColumnHidden API (Format > Columns >
 * Show in LibreOffice to reveal them).  The db-name header row always
 * contains the column name so the importer can find columns by name
 * regardless of whether hidden columns have been revealed.
 */
data class ColumnDef(
    val dbName: String,
    val friendlyName: String,
    val readOnly: Boolean = false,
    val hidden: Boolean = false,
    val dropdownValues: List<String>? = null,
    val isDate: Boolean = false,
)

// ---------------------------------------------------------------------------
// Property display helper
// ---------------------------------------------------------------------------

data class PropertyDisplay(
    val id: Int,
    val display: String,
)

/**
 * Converts a raw address and postcode into a single-line display string
 * safe for use in a spreadsheet dropdown.
 *
 * Commas in the address are replaced with en-dashes so LibreOffice does not
 * wrap the dropdown entry at comma boundaries.
 */
fun propertyDisplayString(address: String, postcode: String): String =
    "${address.replace(",", " \u2013")} $postcode"

// ---------------------------------------------------------------------------
// Column definitions
//
// Dropdown values use the human-readable [label] from each category enum.
// The importer translates labels back to [dbKey] values before persisting.
//
// The id column is visible (not hidden) so users can see which record each
// row corresponds to, and can use the id when deleting or editing rows.
// property_id remains hidden — it is an internal key not useful to users.
// ---------------------------------------------------------------------------

private val EXPENSE_CATEGORIES         = ExpenseCategory.entries.map { it.label }
private val DIVIDEND_INCOME_CATEGORIES = DividendCategory.entries.map { it.label }
private val PROPERTY_INCOME_CATEGORIES = IncomeCategory.entries.map { it.label }
private val SAVINGS_INCOME_CATEGORIES  = SavingsCategory.entries.map { it.label }

private val EXPENSE_COLUMNS = listOf(
    ColumnDef("id",               "ID",               readOnly = true, hidden = false),
    ColumnDef("category",         "Category",         dropdownValues = EXPENSE_CATEGORIES),
    ColumnDef("amount",           "Amount (£)"),
    ColumnDef("description",      "Description"),
    ColumnDef("transaction_date", "Transaction Date",  isDate = true),
)

private val INCOME_DIVIDEND_COLUMNS = listOf(
    ColumnDef("id",               "ID",               readOnly = true, hidden = false),
    ColumnDef("category",         "Category",         dropdownValues = DIVIDEND_INCOME_CATEGORIES),
    ColumnDef("amount",           "Amount (£)"),
    ColumnDef("description",      "Description"),
    ColumnDef("transaction_date", "Transaction Date",  isDate = true),
)

private val INCOME_SAVINGS_COLUMNS = listOf(
    ColumnDef("id",               "ID",               readOnly = true, hidden = false),
    ColumnDef("category",         "Category",         dropdownValues = SAVINGS_INCOME_CATEGORIES),
    ColumnDef("amount",           "Amount (£)"),
    ColumnDef("description",      "Description"),
    ColumnDef("transaction_date", "Transaction Date",  isDate = true),
)

/**
 * Income (property) columns depend on which properties exist.
 *
 * Layout:
 *   col 0  id               – read-only, visible
 *   col 1  property_id      – read-only, hidden
 *   col 2  property         – editable dropdown of address strings
 *   col 3  category         – editable dropdown (shows labels)
 *   col 4  amount           – editable numeric
 *   col 5  description      – editable text
 *   col 6  transaction_date – editable date
 */
fun incomePropertyColumns(propertyDisplayStrings: List<String>): List<ColumnDef> = listOf(
    ColumnDef("id",               "ID",               readOnly = true, hidden = false),
    ColumnDef("property_id",      "Property ID",      readOnly = true, hidden = true),
    ColumnDef("property",         "Property",         dropdownValues = propertyDisplayStrings),
    ColumnDef("category",         "Category",         dropdownValues = PROPERTY_INCOME_CATEGORIES),
    ColumnDef("amount",           "Amount (£)"),
    ColumnDef("description",      "Description"),
    ColumnDef("transaction_date", "Transaction Date",  isDate = true),
)

// ---------------------------------------------------------------------------
// Category label ↔ dbKey helpers
// ---------------------------------------------------------------------------

/**
 * Translates a [dbKey] stored in the database to the human-readable [label]
 * for display in the spreadsheet category column.  If the key is not found
 * the key itself is returned so no data is lost.
 */
fun dbKeyToLabel(table: ExportTable, dbKey: String): String = when (table) {
    ExportTable.EXPENSES         -> ExpenseCategory.entries.firstOrNull  { it.dbKey == dbKey }?.label ?: dbKey
    ExportTable.INCOME_DIVIDENDS -> DividendCategory.entries.firstOrNull { it.dbKey == dbKey }?.label ?: dbKey
    ExportTable.INCOME_PROPERTY  -> IncomeCategory.entries.firstOrNull   { it.dbKey == dbKey }?.label ?: dbKey
    ExportTable.INCOME_SAVINGS   -> SavingsCategory.entries.firstOrNull  { it.dbKey == dbKey }?.label ?: dbKey
}

// ---------------------------------------------------------------------------
// Row fetch helpers
// ---------------------------------------------------------------------------

fun fetchRows(
    table: ExportTable,
    userId: Int,
    taxYear: String,
    filterPropertyId: Int? = null,
    propertyDisplayMap: Map<Int, String> = emptyMap(),
): List<Map<String, Any?>> = transaction {

    val (startDate, endDate) = taxYearDateRange(taxYear)

    when (table) {

        ExportTable.EXPENSES -> {
            var query = ExpenseEntries
                .selectAll()
                .where {
                    (ExpenseEntries.userId eq userId) and
                            ExpenseEntries.supersededAt.isNull() and
                            (ExpenseEntries.transactionDate greaterEq startDate) and
                            (ExpenseEntries.transactionDate lessEq endDate)
                }
            if (filterPropertyId != null)
                query = query.andWhere { ExpenseEntries.propertyId eq filterPropertyId }
            query
                .orderBy(ExpenseEntries.transactionDate)
                .map { row ->
                    mapOf(
                        "id"               to row[ExpenseEntries.id],
                        "category"         to dbKeyToLabel(ExportTable.EXPENSES, row[ExpenseEntries.category]),
                        "amount"           to row[ExpenseEntries.amount],
                        "description"      to row[ExpenseEntries.description],
                        "transaction_date" to row[ExpenseEntries.transactionDate],
                    )
                }
        }

        ExportTable.INCOME_DIVIDENDS -> {
            IncomeDividendEntries
                .selectAll()
                .where {
                    (IncomeDividendEntries.userId eq userId) and
                            (IncomeDividendEntries.taxYear eq taxYear) and
                            IncomeDividendEntries.supersededAt.isNull()
                }
                .orderBy(IncomeDividendEntries.transactionDate)
                .map { row ->
                    mapOf(
                        "id"               to row[IncomeDividendEntries.id],
                        "category"         to dbKeyToLabel(ExportTable.INCOME_DIVIDENDS, row[IncomeDividendEntries.category]),
                        "amount"           to row[IncomeDividendEntries.amount],
                        "description"      to row[IncomeDividendEntries.description],
                        "transaction_date" to row[IncomeDividendEntries.transactionDate],
                    )
                }
        }

        ExportTable.INCOME_PROPERTY -> {
            var query = IncomePropertyEntries
                .selectAll()
                .where {
                    (IncomePropertyEntries.userId eq userId) and
                            IncomePropertyEntries.supersededAt.isNull() and
                            (IncomePropertyEntries.transactionDate greaterEq startDate) and
                            (IncomePropertyEntries.transactionDate lessEq endDate)
                }
            if (filterPropertyId != null)
                query = query.andWhere { IncomePropertyEntries.propertyId eq filterPropertyId }
            query
                .orderBy(IncomePropertyEntries.transactionDate)
                .map { row ->
                    val pid = row[IncomePropertyEntries.propertyId]
                    mapOf(
                        "id"               to row[IncomePropertyEntries.id],
                        "property_id"      to pid,
                        "property"         to (propertyDisplayMap[pid] ?: ""),
                        "category"         to dbKeyToLabel(ExportTable.INCOME_PROPERTY, row[IncomePropertyEntries.category]),
                        "amount"           to row[IncomePropertyEntries.amount],
                        "description"      to row[IncomePropertyEntries.description],
                        "transaction_date" to row[IncomePropertyEntries.transactionDate],
                    )
                }
        }

        ExportTable.INCOME_SAVINGS -> {
            IncomeSavingsEntries
                .selectAll()
                .where {
                    (IncomeSavingsEntries.userId eq userId) and
                            (IncomeSavingsEntries.taxYear eq taxYear) and
                            IncomeSavingsEntries.supersededAt.isNull()
                }
                .orderBy(IncomeSavingsEntries.transactionDate)
                .map { row ->
                    mapOf(
                        "id"               to row[IncomeSavingsEntries.id],
                        "category"         to dbKeyToLabel(ExportTable.INCOME_SAVINGS, row[IncomeSavingsEntries.category]),
                        "amount"           to row[IncomeSavingsEntries.amount],
                        "description"      to row[IncomeSavingsEntries.description],
                        "transaction_date" to row[IncomeSavingsEntries.transactionDate],
                    )
                }
        }
    }
}

// ---------------------------------------------------------------------------
// Spreadsheet writer
// ---------------------------------------------------------------------------

object SpreadsheetExporter {

    private val TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy.MM.dd-HH.mm")

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    fun exportSingle(
        table: ExportTable,
        cols: List<ColumnDef>,
        rows: List<Map<String, Any?>>,
        outputPath: Path,
    ) {
        val wb = XSSFWorkbook()
        addSheet(wb, table, cols, rows)
        writeWorkbook(wb, outputPath)
    }

    fun exportAll(
        entries: Map<ExportTable, Pair<List<ColumnDef>, List<Map<String, Any?>>>>,
        outputPath: Path,
    ) {
        val wb = XSSFWorkbook()
        for (table in ExportTable.entries) {
            val (cols, rows) = entries[table] ?: (staticColumnsFor(table) to emptyList())
            addSheet(wb, table, cols, rows)
        }
        writeWorkbook(wb, outputPath)
    }

    fun suggestedFileName(table: ExportTable, qualifier: String): String {
        val ts = LocalDateTime.now().format(TIMESTAMP_FMT)
        return "${table.fileName}_${qualifier}_at_$ts.xlsx"
    }

    fun suggestedAllFileName(): String {
        val ts = LocalDateTime.now().format(TIMESTAMP_FMT)
        return "all_tables_at_$ts.xlsx"
    }

    fun staticColumnsFor(table: ExportTable): List<ColumnDef> = when (table) {
        ExportTable.EXPENSES         -> EXPENSE_COLUMNS
        ExportTable.INCOME_DIVIDENDS -> INCOME_DIVIDEND_COLUMNS
        ExportTable.INCOME_PROPERTY  -> incomePropertyColumns(emptyList())
        ExportTable.INCOME_SAVINGS   -> INCOME_SAVINGS_COLUMNS
    }

    // ------------------------------------------------------------------
    // Sheet builder
    // ------------------------------------------------------------------

    private fun addSheet(
        wb: XSSFWorkbook,
        table: ExportTable,
        cols: List<ColumnDef>,
        rows: List<Map<String, Any?>>,
    ) {
        val sheet  = wb.createSheet(table.label)
        val styles = Styles(wb)

        // ── Row 0: friendly labels (visible) ───────────────────────────
        val friendlyRow = sheet.createRow(0)
        cols.forEachIndexed { ci, col ->
            val cell = friendlyRow.createCell(ci)
            cell.setCellValue(if (col.hidden) "" else col.friendlyName)
            cell.cellStyle = if (col.readOnly || col.hidden) styles.headerReadOnly
            else styles.headerEditable
        }

        // ── Row 1: db column names (hidden — used by importer only) ────
        // The row is written so the importer can locate columns by their
        // database name.  zeroHeight hides it from the user in LibreOffice.
        val dbRow = sheet.createRow(1)
        cols.forEachIndexed { ci, col ->
            val cell = dbRow.createCell(ci)
            cell.setCellValue(col.dbName)
            cell.cellStyle = styles.headerReadOnly
        }
        dbRow.zeroHeight = true

        // ── Data rows ──────────────────────────────────────────────────
        rows.forEachIndexed { ri, rowData ->
            val sheetRow = sheet.createRow(ri + 2)
            cols.forEachIndexed { ci, col ->
                val cell  = sheetRow.createCell(ci)
                val value = rowData[col.dbName]
                when {
                    col.readOnly || col.hidden -> {
                        if (value != null) cell.setCellValue((value as Number).toDouble())
                        cell.cellStyle = styles.lockedGrey
                    }
                    col.isDate -> {
                        val dateStr = value?.toString()
                        if (!dateStr.isNullOrBlank()) {
                            runCatching { LocalDate.parse(dateStr) }.getOrNull()?.let { ld ->
                                cell.setCellValue(ld)
                            } ?: cell.setCellValue(dateStr)
                        }
                        cell.cellStyle = styles.dateCell
                    }
                    value is Number -> {
                        cell.setCellValue(value.toDouble())
                        cell.cellStyle = styles.numeric
                    }
                    else -> {
                        cell.setCellValue(value?.toString() ?: "")
                        cell.cellStyle = styles.editable
                    }
                }
            }
        }

        // ── Data validation dropdowns ───────────────────────────────────
        cols.forEachIndexed { ci, col ->
            if (!col.dropdownValues.isNullOrEmpty() && !col.hidden) {
                addDropdown(sheet, ci, col.dropdownValues)
            }
        }

        // ── Column widths and hidden state ─────────────────────────────
        cols.forEachIndexed { ci, col ->
            if (col.hidden) {
                sheet.setColumnWidth(ci, when (col.dbName) {
                    "property_id" -> 14 * 256
                    else          -> 20 * 256
                })
                sheet.setColumnHidden(ci, true)
            } else {
                sheet.setColumnWidth(ci, when (col.dbName) {
                    "id"               ->  6 * 256
                    "amount"           -> 14 * 256
                    "transaction_date" -> 16 * 256
                    "category"         -> 32 * 256
                    "property"         -> 40 * 256
                    "description"      -> 48 * 256
                    else               -> 20 * 256
                })
            }
        }

        // ── Freeze top header row ──────────────────────────────────────
        // Freeze rows 0 and 1 so the friendly header stays visible while
        // scrolling.  Row 1 (db names) is zero-height so effectively invisible.
        sheet.createFreezePane(0, 2)

        // ── Unlock editable columns ────────────────────────────────────
        cols.forEachIndexed { ci, col ->
            if (!col.readOnly && !col.hidden) {
                val defaultStyle = (wb.createCellStyle() as XSSFCellStyle).apply {
                    locked = false
                    when (col.dbName) {
                        "amount" ->
                            dataFormat = wb.creationHelper
                                .createDataFormat().getFormat("#,##0.00")
                        "transaction_date" -> {
                            dataFormat = wb.creationHelper
                                .createDataFormat().getFormat("yyyy-mm-dd")
                            alignment  = HorizontalAlignment.CENTER
                        }
                    }
                }
                sheet.setDefaultColumnStyle(ci, defaultStyle)

                for (ri in 2..sheet.lastRowNum) {
                    val cell = sheet.getRow(ri)?.getCell(ci) ?: continue
                    cell.cellStyle = (wb.createCellStyle() as XSSFCellStyle).apply {
                        locked = false
                        when (col.dbName) {
                            "amount" ->
                                dataFormat = wb.creationHelper
                                    .createDataFormat().getFormat("#,##0.00")
                            "transaction_date" -> {
                                dataFormat = wb.creationHelper
                                    .createDataFormat().getFormat("yyyy-mm-dd")
                                alignment  = HorizontalAlignment.CENTER
                            }
                        }
                    }
                }
            }
        }

        // ── Sheet protection ───────────────────────────────────────────
        sheet.protectSheet("")
    }

    // ------------------------------------------------------------------
    // Dropdown helper
    // ------------------------------------------------------------------

    private fun addDropdown(sheet: XSSFSheet, colIndex: Int, values: List<String>) {
        val wb          = sheet.workbook
        val dvHelper    = sheet.dataValidationHelper
        val addressList = CellRangeAddressList(2, 1000, colIndex, colIndex)

        val constraint = if (values.joinToString(",").length <= 255) {
            dvHelper.createExplicitListConstraint(values.toTypedArray())
        } else {
            val refSheetName = "_dv_${sheet.sheetName}_c$colIndex"
                .replace(" ", "_")
                .take(31)
            val refSheet = wb.getSheet(refSheetName)
                ?: wb.createSheet(refSheetName).also { rs ->
                    wb.setSheetHidden(wb.getSheetIndex(rs), true)
                    values.forEachIndexed { i, v ->
                        rs.createRow(i).createCell(0).setCellValue(v)
                    }
                }
            val lastRow = refSheet.lastRowNum + 1
            dvHelper.createFormulaListConstraint("'$refSheetName'!\$A\$1:\$A\$$lastRow")
        }

        val dv = dvHelper.createValidation(constraint, addressList)
        dv.showErrorBox = true
        dv.errorStyle   = DataValidation.ErrorStyle.STOP
        dv.createErrorBox("Invalid value", "Please select a value from the dropdown list.")
        sheet.addValidationData(dv)
    }

    // ------------------------------------------------------------------
    // Write to disk
    // ------------------------------------------------------------------

    private fun writeWorkbook(wb: XSSFWorkbook, path: Path) {
        FileOutputStream(path.toFile()).use { wb.write(it) }
        wb.close()
    }

    // ------------------------------------------------------------------
    // Cell styles
    // ------------------------------------------------------------------

    private class Styles(wb: XSSFWorkbook) {
        private val cf = wb.creationHelper

        val headerEditable: XSSFCellStyle = (wb.createCellStyle() as XSSFCellStyle).apply {
            fillForegroundColor = IndexedColors.CORNFLOWER_BLUE.index
            fillPattern         = FillPatternType.SOLID_FOREGROUND
            setFont(wb.createFont().also { it.bold = true; it.color = IndexedColors.WHITE.index })
            locked = true
        }

        val headerReadOnly: XSSFCellStyle = (wb.createCellStyle() as XSSFCellStyle).apply {
            fillForegroundColor = IndexedColors.GREY_50_PERCENT.index
            fillPattern         = FillPatternType.SOLID_FOREGROUND
            setFont(wb.createFont().also { it.bold = true; it.color = IndexedColors.WHITE.index })
            locked = true
        }

        val lockedGrey: XSSFCellStyle = (wb.createCellStyle() as XSSFCellStyle).apply {
            fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
            fillPattern         = FillPatternType.SOLID_FOREGROUND
            locked              = true
        }

        val editable: XSSFCellStyle = (wb.createCellStyle() as XSSFCellStyle).apply {
            locked = false
        }

        val numeric: XSSFCellStyle = (wb.createCellStyle() as XSSFCellStyle).apply {
            locked     = false
            dataFormat = cf.createDataFormat().getFormat("#,##0.00")
        }

        val dateCell: XSSFCellStyle = (wb.createCellStyle() as XSSFCellStyle).apply {
            locked     = false
            dataFormat = cf.createDataFormat().getFormat("yyyy-mm-dd")
            alignment  = HorizontalAlignment.CENTER
        }
    }
}
