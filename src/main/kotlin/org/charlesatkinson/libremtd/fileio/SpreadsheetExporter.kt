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

import org.apache.poi.ss.usermodel.DataValidation
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.util.CellRangeAddressList
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.charlesatkinson.libremtd.database.PropertyRepository
import org.charlesatkinson.libremtd.fileio.components.ColumnDef
import org.charlesatkinson.libremtd.fileio.components.ColumnType
import org.charlesatkinson.libremtd.fileio.components.ExportTable
import org.charlesatkinson.libremtd.fileio.components.sheetSpecFor
import java.io.FileOutputStream
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------------------
// Sheet data
// ---------------------------------------------------------------------------

/** The columns and rows of one sheet, ready to write. */
data class SheetData(
    val columns: List<ColumnDef>,
    val rows: List<Map<String, Any?>>,
)

/**
 * The single-line text used to identify a property, both in the property
 * selectors of the GUI and in the spreadsheet's property dropdown and cells.
 * The importer matches spreadsheet cells against this same text.
 *
 * UK properties (postcode present) are "address postcode", following the UK
 * convention of separating every element except the postcode with commas,
 * so any commas belong to the address as the user typed it, for example
 * "42 Imaginary Crescent, Madeup-on-Sea MS2 7UP". Foreign properties show
 * their ISO 3166-1 Alpha-3 country code in parentheses instead, if one is
 * available.
 *
 * The address is deliberately not altered. A comma inside a spreadsheet
 * dropdown entry would split it, but the exporter handles that itself by
 * putting such lists on a hidden helper sheet.
 */
fun propertyDisplayString(address: String, postcode: String?, countryCode: String? = null): String {
    val trimmed = address.trim()
    val suffix = when {
        !postcode.isNullOrBlank()    -> postcode
        !countryCode.isNullOrBlank() -> "($countryCode)"
        else                          -> null
    }
    return if (suffix != null) "$trimmed $suffix" else trimmed
}

/**
 * Translates a [dbKey] stored in the database to the human-readable label
 * shown in the spreadsheet. If the key is unknown the key itself is
 * returned, so no data is lost.
 */
fun dbKeyToLabel(table: ExportTable, dbKey: String): String =
    sheetSpecFor(table).categories.firstOrNull { it.dbKey == dbKey }?.label ?: dbKey

/**
 * Current rows for [table] and [taxYear], in export form: categories as
 * labels, a "property" display string where the sheet has a property column,
 * oldest transaction first. [filterPropertyId] is applied only to sheets
 * which have a property column.
 */
fun fetchRows(
    table: ExportTable,
    userId: Int,
    taxYear: String,
    filterPropertyId: Int? = null,
    propertyDisplayMap: Map<Int, String> = emptyMap(),
): List<Map<String, Any?>> {
    val spec = sheetSpecFor(table)
    val hasProperty = spec.propertyType != null

    return spec.currentRows(userId, taxYear)
        .filter { !hasProperty || filterPropertyId == null || it["property_id"] == filterPropertyId }
        .sortedWith(
            compareBy<Map<String, Any?>>(
                { it["transaction_date"] as? String ?: "" },
                { (it["id"] as Number).toInt() },
            )
        )
        .map { row ->
            val out = row.toMutableMap()
            out["category"] = dbKeyToLabel(table, row["category"] as String)
            if (hasProperty) out["property"] = propertyDisplayMap[row["property_id"]] ?: ""
            out
        }
}

/**
 * Loads everything needed to write one sheet: the columns (with a property
 * dropdown limited to properties of the type the sheet accepts) and the
 * current rows.
 */
fun sheetDataFor(
    table: ExportTable,
    userId: Int,
    taxYear: String,
    filterPropertyId: Int? = null,
): SheetData {
    val spec = sheetSpecFor(table)
    val propertyType = spec.propertyType
    val displayMap: Map<Int, String> =
        if (propertyType == null) emptyMap()
        else PropertyRepository.findByUser(userId)
            .filter { it.propertyType == propertyType }
            .associate { it.id to propertyDisplayString(it.address, it.postcode, it.countryCode) }

    val columns = spec.columns(displayMap.values.distinct().sorted())
    return SheetData(columns, fetchRows(table, userId, taxYear, filterPropertyId, displayMap))
}

// ---------------------------------------------------------------------------
// Spreadsheet writer
// ---------------------------------------------------------------------------

object SpreadsheetExporter {

    private val TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy.MM.dd-HH.mm")

    /**
     * The green LibreMTD uses as its accent in the green theme (#00703c),
     * used for the headers of editable columns. White bold text on it
     * has a contrast ratio of about 6.2:1.
     */
    private val LIBREMTD_GREEN_RGB = byteArrayOf(0x00, 0x70, 0x3c)

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Writes one sheet per entry in [tables] to [outputPath]. */
    fun exportTables(
        tables: List<ExportTable>,
        userId: Int,
        taxYear: String,
        filterPropertyId: Int?,
        outputPath: Path,
    ) {
        val data = tables.associateWith { sheetDataFor(it, userId, taxYear, filterPropertyId) }
        buildWorkbook(data).use { wb ->
            FileOutputStream(outputPath.toFile()).use { wb.write(it) }
        }
    }

    /**
     * Builds the workbook in memory, one sheet per entry, in map order.
     * Separate from writing to disk so tests can inspect it directly.
     */
    fun buildWorkbook(entries: Map<ExportTable, SheetData>): XSSFWorkbook {
        val wb     = XSSFWorkbook()
        val styles = Styles(wb)
        for ((table, data) in entries) {
            addSheet(wb, styles, table, data.columns, data.rows)
        }
        return wb
    }

    fun suggestedFileName(table: ExportTable, qualifier: String): String {
        val ts = LocalDateTime.now().format(TIMESTAMP_FMT)
        return "${table.fileName}_${qualifier}_at_$ts.xlsx"
    }

    fun suggestedAllFileName(): String {
        val ts = LocalDateTime.now().format(TIMESTAMP_FMT)
        return "all_tables_at_$ts.xlsx"
    }

    // ------------------------------------------------------------------
    // Sheet builder
    // ------------------------------------------------------------------

    private fun addSheet(
        wb: XSSFWorkbook,
        styles: Styles,
        table: ExportTable,
        cols: List<ColumnDef>,
        rows: List<Map<String, Any?>>,
    ) {
        val sheet = wb.createSheet(table.label)

        // Row 0: friendly labels (visible)
        val friendlyRow = sheet.createRow(0)
        cols.forEachIndexed { ci, col ->
            val cell = friendlyRow.createCell(ci)
            cell.setCellValue(if (col.hidden) "" else col.friendlyName)
            cell.cellStyle = if (col.readOnly || col.hidden) styles.headerReadOnly
            else styles.headerEditable
        }

        // Row 1: db column names (zero height; used by the importer only)
        val dbRow = sheet.createRow(1)
        cols.forEachIndexed { ci, col ->
            val cell = dbRow.createCell(ci)
            cell.setCellValue(col.dbName)
            cell.cellStyle = styles.headerReadOnly
        }
        dbRow.zeroHeight = true

        // Data rows
        rows.forEachIndexed { ri, rowData ->
            val sheetRow = sheet.createRow(ri + 2)
            cols.forEachIndexed { ci, col ->
                val cell  = sheetRow.createCell(ci)
                val value = rowData[col.dbName]
                when {
                    col.readOnly || col.hidden -> {
                        if (value is Number) cell.setCellValue(value.toDouble())
                        cell.cellStyle = styles.lockedGrey
                    }
                    col.type == ColumnType.DATE -> {
                        val dateStr = value?.toString()
                        if (!dateStr.isNullOrBlank()) {
                            val parsed = runCatching { LocalDate.parse(dateStr) }.getOrNull()
                            if (parsed != null) cell.setCellValue(parsed) else cell.setCellValue(dateStr)
                        }
                        cell.cellStyle = styles.dateCell
                    }
                    col.type == ColumnType.BOOLEAN -> {
                        cell.setCellValue(if (value == true) "Yes" else "No")
                        cell.cellStyle = styles.editable
                    }
                    col.type == ColumnType.MONEY -> {
                        // A null (an optional amount not entered) leaves the cell blank.
                        if (value is Number) cell.setCellValue(value.toDouble())
                        cell.cellStyle = styles.numeric
                    }
                    else -> {
                        cell.setCellValue(value?.toString() ?: "")
                        cell.cellStyle = styles.editable
                    }
                }
            }
        }

        // Dropdowns
        val lastDropdownRow = maxOf(1000, rows.size + 502)
        cols.forEachIndexed { ci, col ->
            val values = col.dropdownValues
            if (!values.isNullOrEmpty() && !col.hidden) {
                addDropdown(sheet, ci, values, lastDropdownRow)
            }
        }

        // Column widths, hidden state, and a default style for every column
        // so that rows the user adds below the data behave like the
        // exported ones under sheet protection: unlocked for editable
        // columns, locked and grey for read-only and hidden ones (so the ID
        // cell of a new row cannot be typed into). One shared style per
        // column type: no per-cell styles.
        cols.forEachIndexed { ci, col ->
            sheet.setColumnWidth(ci, widthFor(col) * 256)
            if (col.hidden) {
                sheet.setColumnHidden(ci, true)
                sheet.setDefaultColumnStyle(ci, styles.lockedGrey)
            } else if (col.readOnly) {
                sheet.setDefaultColumnStyle(ci, styles.lockedGrey)
            } else {
                sheet.setDefaultColumnStyle(ci, styles.forType(col.type))
            }
        }

        // Freeze the two header rows (row 1 has zero height).
        sheet.createFreezePane(0, 2)

        sheet.protectSheet("")
    }

    private fun widthFor(col: ColumnDef): Int = when {
        col.hidden         -> 14
        col.dbName == "id" -> 6
        else -> when (col.type) {
            ColumnType.MONEY        -> 20
            ColumnType.DATE         -> 16
            ColumnType.CATEGORY     -> 32
            ColumnType.PROPERTY     -> 48
            ColumnType.TEXT         -> 48
            ColumnType.INTEGER      -> 12
            ColumnType.BOOLEAN      -> 10
            ColumnType.COUNTRY_CODE -> 10
        }
    }

    // ------------------------------------------------------------------
    // Dropdown helper
    // ------------------------------------------------------------------

    /**
     * Adds a dropdown to column [colIndex].
     *
     * A short list with no commas in it is stored inline. An inline list is
     * saved as one comma-separated string, so Excel and LibreOffice would
     * split any entry containing a comma (such as "42 Imaginary Crescent,
     * Madeup-on-Sea MS2 7UP") into two, and Excel also limits the string to
     * 255 characters. Any other list goes on a hidden helper sheet instead.
     * The helper sheet's name is numbered rather than derived from the main
     * sheet's name, because sheet names are limited to 31 characters and a
     * truncated derived name could collide with another column's.
     */
    private fun addDropdown(sheet: XSSFSheet, colIndex: Int, values: List<String>, lastRow: Int) {
        val wb          = sheet.workbook
        val dvHelper    = sheet.dataValidationHelper
        val addressList = CellRangeAddressList(2, lastRow, colIndex, colIndex)

        val needsHelperSheet = values.any { ',' in it } || values.joinToString(",").length > 255
        val constraint = if (!needsHelperSheet) {
            dvHelper.createExplicitListConstraint(values.toTypedArray())
        } else {
            val refSheetName = "_dv_${wb.numberOfSheets}"
            val refSheet = wb.createSheet(refSheetName)
            wb.setSheetHidden(wb.getSheetIndex(refSheet), true)
            values.forEachIndexed { i, v ->
                refSheet.createRow(i).createCell(0).setCellValue(v)
            }
            dvHelper.createFormulaListConstraint("'$refSheetName'!\$A\$1:\$A\$${values.size}")
        }

        val dv = dvHelper.createValidation(constraint, addressList)
        dv.showErrorBox = true
        dv.errorStyle   = DataValidation.ErrorStyle.STOP
        dv.createErrorBox("Invalid value", "Please select a value from the dropdown list.")
        sheet.addValidationData(dv)
    }

    // ------------------------------------------------------------------
    // Cell styles (created once per workbook)
    //
    // Each style sets applyProtection explicitly. POI writes the locked or
    // unlocked setting but not that flag, and an application is entitled to
    // ignore a style's protection settings when it is absent.
    // ------------------------------------------------------------------

    private class Styles(wb: XSSFWorkbook) {
        private val cf = wb.creationHelper

        val headerEditable: XSSFCellStyle = (wb.createCellStyle() as XSSFCellStyle).apply {
            setFillForegroundColor(XSSFColor(LIBREMTD_GREEN_RGB, null))
            fillPattern         = FillPatternType.SOLID_FOREGROUND
            setFont(wb.createFont().also { it.bold = true; it.color = IndexedColors.WHITE.index })
            locked = true
            coreXf.applyProtection = true
        }

        val headerReadOnly: XSSFCellStyle = (wb.createCellStyle() as XSSFCellStyle).apply {
            fillForegroundColor = IndexedColors.GREY_50_PERCENT.index
            fillPattern         = FillPatternType.SOLID_FOREGROUND
            setFont(wb.createFont().also { it.bold = true; it.color = IndexedColors.WHITE.index })
            locked = true
            coreXf.applyProtection = true
        }

        val lockedGrey: XSSFCellStyle = (wb.createCellStyle() as XSSFCellStyle).apply {
            fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
            fillPattern         = FillPatternType.SOLID_FOREGROUND
            locked              = true
            coreXf.applyProtection = true
        }

        val editable: XSSFCellStyle = (wb.createCellStyle() as XSSFCellStyle).apply {
            locked = false
            coreXf.applyProtection = true
        }

        val numeric: XSSFCellStyle = (wb.createCellStyle() as XSSFCellStyle).apply {
            locked     = false
            dataFormat = cf.createDataFormat().getFormat("#,##0.00")
            coreXf.applyProtection = true
        }

        val dateCell: XSSFCellStyle = (wb.createCellStyle() as XSSFCellStyle).apply {
            locked     = false
            dataFormat = cf.createDataFormat().getFormat("yyyy-mm-dd")
            alignment  = HorizontalAlignment.CENTER
            coreXf.applyProtection = true
        }

        fun forType(type: ColumnType): XSSFCellStyle = when (type) {
            ColumnType.MONEY -> numeric
            ColumnType.DATE  -> dateCell
            else             -> editable
        }
    }
}
