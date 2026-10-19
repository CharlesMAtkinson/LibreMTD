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

import mu.KotlinLogging
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.charlesatkinson.libremtd.database.PropertyRepository
import org.charlesatkinson.libremtd.database.PropertyType
import org.charlesatkinson.libremtd.database.SubmissionRepository
import org.charlesatkinson.libremtd.database.components.FinalDeclarationLockedException
import org.charlesatkinson.libremtd.database.taxYearDateRange
import org.charlesatkinson.libremtd.fileio.components.ALL_SHEET_SPECS
import org.charlesatkinson.libremtd.fileio.components.ColumnDef
import org.charlesatkinson.libremtd.fileio.components.ColumnType
import org.charlesatkinson.libremtd.fileio.components.SheetSpec
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlin.io.path.inputStream

private val logger = KotlinLogging.logger {}

private val COUNTRY_CODE_REGEX = Regex("^[A-Z]{3}$")

// ---------------------------------------------------------------------------
// Public result types
// ---------------------------------------------------------------------------

/**
 * [skipped] counts new rows left out because they duplicated an existing
 * record and the user chose to skip duplicates.
 */
data class ImportSummary(
    val inserted:   Int,
    val superseded: Int,
    val unchanged:  Int,
    val deleted:    Int,
    val warnings:   List<String>,
    val skipped:    Int = 0,
)

sealed class ImportResult {
    data class Success(val summary: ImportSummary) : ImportResult()
    data class Failure(val errors:  List<String>)  : ImportResult()

    /**
     * Possible duplicates found. The caller must present them to the user
     * and call one of the two callbacks, or neither to cancel:
     * [proceed] inserts the duplicates as additional records, and
     * [proceedSkippingDuplicates] imports everything else and leaves the
     * duplicate rows out.
     */
    data class ConflictsFound(
        val conflicts:                 List<ConflictRow>,
        val proceed:                   () -> ImportResult,
        val proceedSkippingDuplicates: () -> ImportResult,
    ) : ImportResult()
}

/**
 * One spreadsheet row that matches an existing database record on every
 * compared field even though the row carries no id. [summary] is a short
 * human-readable rendering of the row's values.
 */
data class ConflictRow(
    val sheetName:    String,
    val sheetRowNum:  Int,
    val existingDbId: Int,
    val summary:      String,
)

// ---------------------------------------------------------------------------
// Internal types
// ---------------------------------------------------------------------------

internal data class PropertyRef(
    val id:      Int,
    val display: String,
    val type:    PropertyType,
)

/**
 * One parsed data row. [values] is keyed by column db name and holds typed
 * values (category as its database key, amount as Double, date as an ISO
 * string, property as "property_id" plus its display text). It is empty for
 * delete rows.
 */
internal data class ParsedRow(
    val sheetRowNum: Int,
    val id:          Int?,
    val isDelete:    Boolean,
    val values:      Map<String, Any?>,
)

private data class ParsedSheet(
    val spec:     SheetSpec,
    val cols:     List<ColumnDef>,
    val existing: Map<Int, Map<String, Any?>>,
    val rows:     List<ParsedRow>,
)

// ---------------------------------------------------------------------------
// SpreadsheetImporter
// ---------------------------------------------------------------------------

object SpreadsheetImporter {

    /**
     * Imports from [file]. Every recognised sheet present in the workbook is
     * imported, in one transaction: either everything is written or nothing
     * is. Unrecognised sheets (including hidden helper sheets) are ignored.
     */
    fun import(file: Path, userId: Int, taxYear: String): ImportResult {
        val wb = openWorkbook(file) ?: return ImportResult.Failure(
            listOf("Cannot open file.  Is it a valid .xlsx spreadsheet?")
        )
        return wb.use { importWorkbook(it, userId, taxYear) }
    }

    /**
     * The import itself, separate from opening a file so tests can import an
     * in-memory workbook. [specs] is a parameter so a test can substitute a
     * spec that fails part-way, to prove the transaction rolls back.
     *
     * Writes go through the entry repositories, so the Final Declaration
     * guard and the supersede history apply exactly as they do in the GUI.
     */
    internal fun importWorkbook(
        wb:      Workbook,
        userId:  Int,
        taxYear: String,
        specs:   List<SheetSpec> = ALL_SHEET_SPECS,
    ): ImportResult {
        val present = specs.filter { wb.getSheet(it.table.label) != null }
        if (present.isEmpty()) {
            return ImportResult.Failure(
                listOf(
                    "No recognised sheet found.  Expected one or more of: " +
                            specs.joinToString { "\"${it.table.label}\"" } + "."
                )
            )
        }

        if (SubmissionRepository.isFinalDeclared(userId, taxYear)) {
            return ImportResult.Failure(
                listOf("Tax year $taxYear has been finally declared and can no longer be amended.  Nothing was imported.")
            )
        }

        val properties = buildPropertyRefs(userId)
        val errors     = mutableListOf<String>()
        val parsed     = mutableListOf<ParsedSheet>()

        for (spec in present) {
            val sheet = wb.getSheet(spec.table.label)
            val cols  = spec.columns(emptyList())

            val errorsBefore = errors.size
            validateHeaders(sheet, cols, errors)
            if (errors.size > errorsBefore) continue

            val existing = spec.currentRows(userId, taxYear)
                .associateBy { (it["id"] as Number).toInt() }
            val rows = parseSheet(sheet, spec, cols, taxYear, properties, existing, errors)
            parsed += ParsedSheet(spec, cols, existing, rows)
        }

        if (errors.isNotEmpty()) return ImportResult.Failure(errors)

        val conflicts = parsed.flatMap { findConflicts(it) }
        return if (conflicts.isNotEmpty()) {
            ImportResult.ConflictsFound(
                conflicts                 = conflicts,
                proceed                   = { persistAll(parsed, userId, taxYear) },
                proceedSkippingDuplicates = {
                    persistAll(withoutConflicts(parsed, conflicts), userId, taxYear, conflicts.size)
                },
            )
        } else {
            persistAll(parsed, userId, taxYear)
        }
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /**
     * Writes every parsed row inside one outer transaction. The repositories
     * open their own transaction blocks, which join this one, so any
     * exception rolls back everything written so far. [skipped] is only
     * carried into the summary.
     */
    private fun persistAll(
        parsed:  List<ParsedSheet>,
        userId:  Int,
        taxYear: String,
        skipped: Int = 0,
    ): ImportResult =
        try {
            val summary = transaction {
                var inserted = 0; var superseded = 0; var unchanged = 0; var deleted = 0

                for (sheet in parsed) {
                    for (row in sheet.rows) {
                        val id = row.id
                        when {
                            row.isDelete -> {
                                sheet.spec.delete(id!!)
                                deleted++
                            }
                            id == null -> {
                                sheet.spec.insert(userId, taxYear, row.values)
                                inserted++
                            }
                            hasChanged(sheet, id, row) -> {
                                sheet.spec.edit(id, userId, taxYear, row.values)
                                superseded++
                                inserted++
                            }
                            else -> unchanged++
                        }
                    }
                }
                ImportSummary(inserted, superseded, unchanged, deleted, emptyList(), skipped)
            }
            ImportResult.Success(summary)
        } catch (e: FinalDeclarationLockedException) {
            ImportResult.Failure(listOf("${e.message}  Nothing was imported."))
        } catch (e: Exception) {
            logger.error(e) { "Spreadsheet import failed and was rolled back" }
            ImportResult.Failure(
                listOf("Import failed and was rolled back: ${e.message ?: e.javaClass.simpleName}")
            )
        }

    private fun hasChanged(sheet: ParsedSheet, id: Int, row: ParsedRow): Boolean {
        val existing = sheet.existing[id] ?: return true
        return sheet.spec.comparedKeys.any { row.values[it] != existing[it] }
    }

    /** [parsed] without the rows reported in [conflicts]. */
    private fun withoutConflicts(parsed: List<ParsedSheet>, conflicts: List<ConflictRow>): List<ParsedSheet> {
        val skip = conflicts.map { it.sheetName to it.sheetRowNum }.toSet()
        return parsed.map { sheet ->
            sheet.copy(rows = sheet.rows.filterNot { (sheet.spec.table.label to it.sheetRowNum) in skip })
        }
    }

    // ------------------------------------------------------------------
    // Conflict detection
    // ------------------------------------------------------------------

    /**
     * New rows (no id, not delete) that duplicate an existing live row on
     * every compared field. Delete rows target a specific id and cannot
     * conflict.
     */
    private fun findConflicts(sheet: ParsedSheet): List<ConflictRow> =
        sheet.rows.filter { it.id == null && !it.isDelete }.mapNotNull { row ->
            val match = sheet.existing.values.firstOrNull { existing ->
                sheet.spec.comparedKeys.all { key -> existing[key] == row.values[key] }
            }
            match?.let {
                ConflictRow(
                    sheetName    = sheet.spec.table.label,
                    sheetRowNum  = row.sheetRowNum,
                    existingDbId = (it["id"] as Number).toInt(),
                    summary      = summarise(sheet.spec, sheet.cols, row.values),
                )
            }
        }

    private fun summarise(spec: SheetSpec, cols: List<ColumnDef>, values: Map<String, Any?>): String =
        cols.filter { !it.hidden && !it.readOnly }.joinToString(" | ") { col ->
            when (col.type) {
                ColumnType.CATEGORY ->
                    spec.categories.firstOrNull { it.dbKey == values[col.dbName] }?.label
                        ?: values[col.dbName]?.toString().orEmpty()
                ColumnType.MONEY ->
                    (values[col.dbName] as? Double)?.let { "%.2f".format(it) } ?: ""
                ColumnType.BOOLEAN ->
                    if (values[col.dbName] == true) "Yes" else "No"
                else ->
                    values[col.dbName]?.toString().orEmpty()
            }
        }

    // ------------------------------------------------------------------
    // Workbook open
    // ------------------------------------------------------------------

    private fun openWorkbook(file: Path): XSSFWorkbook? = try {
        file.inputStream().use { XSSFWorkbook(it) }
    } catch (_: Exception) { null }
}

// ---------------------------------------------------------------------------
// Property lookup
// ---------------------------------------------------------------------------

internal fun buildPropertyRefs(userId: Int): List<PropertyRef> =
    PropertyRepository.findByUser(userId).map {
        PropertyRef(
            id      = it.id,
            display = propertyDisplayString(it.address, it.postcode, it.countryCode),
            type    = it.propertyType,
        )
    }

private fun typeWord(type: PropertyType): String = when (type) {
    PropertyType.UK      -> "UK"
    PropertyType.FOREIGN -> "foreign"
}

private fun withoutDigits(text: String): String = text.filterNot { it.isDigit() }

/**
 * The error for a property cell that matches no property. If exactly one
 * property of the right type matches once digits are ignored (for example
 * "43 High Street" for "42 High Street"), it is offered as a suggestion.
 * It is never accepted silently, because a genuine typo would then be
 * assigned to the wrong property without anyone noticing.
 */
private fun propertyNotFoundMessage(
    propText:   String,
    properties: List<PropertyRef>,
    wantedType: PropertyType?,
): String {
    val base = "Property \"$propText\" not found.  " +
            "It may have been deleted, or its address edited, since the export."
    val similar = properties.filter {
        (wantedType == null || it.type == wantedType) &&
                withoutDigits(it.display) == withoutDigits(propText)
    }
    return if (similar.size == 1) {
        "$base  Did you mean \"${similar.single().display}\"?"
    } else {
        base
    }
}

/**
 * Resolves the property cell to a [PropertyRef]. The dropdown text wins, so
 * changing it on an existing row moves the entry to another property. The
 * hidden property id is only a fallback when the text is blank, and a
 * tie-breaker if two properties share a display string.
 *
 * If both are blank and the user has exactly one property of the type the
 * sheet accepts, that property is used: there is nothing to choose.
 */
private fun resolveProperty(
    propText:     String,
    pidFromCell:  Int?,
    properties:   List<PropertyRef>,
    wantedType:   PropertyType?,
    err:          (String) -> Unit,
): PropertyRef? {
    val ref: PropertyRef? = when {
        propText.isNotBlank() -> {
            val matches = properties.filter { it.display == propText }
            when {
                matches.isEmpty() -> {
                    err(propertyNotFoundMessage(propText, properties, wantedType))
                    null
                }
                matches.size == 1 -> matches.single()
                else -> matches.firstOrNull { it.id == pidFromCell } ?: run {
                    err("Property \"$propText\" matches more than one of your properties.")
                    null
                }
            }
        }
        pidFromCell != null ->
            properties.firstOrNull { it.id == pidFromCell } ?: run {
                err("Property ID $pidFromCell does not match any of your properties.")
                null
            }
        else -> {
            val candidates = properties.filter { wantedType == null || it.type == wantedType }
            if (candidates.size == 1) {
                candidates.single()
            } else {
                err("Property must not be blank.")
                null
            }
        }
    }

    if (ref != null && wantedType != null && ref.type != wantedType) {
        err(
            "Property \"${ref.display}\" is a ${typeWord(ref.type)} property, " +
                    "but this sheet is for ${typeWord(wantedType)} properties."
        )
        return null
    }
    return ref
}

// ---------------------------------------------------------------------------
// Header validation
// ---------------------------------------------------------------------------

internal fun validateHeaders(
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

// ---------------------------------------------------------------------------
// Row parsing (needs no database)
// ---------------------------------------------------------------------------

/**
 * Parses and validates the data rows of [sheet] (row index 2 onwards).
 *
 * A row with an id and every other non-hidden cell blank is a delete. A row
 * with an id is an edit, and a row without one is new. Completely blank rows
 * are skipped. Errors are appended to [errors]; a row with any error is
 * left out of the result.
 *
 * [existing] is the current rows for the tax year keyed by id. If non-null,
 * ids are checked against it (so an id belonging to another user or another
 * tax year is rejected). Pass null to skip that check.
 */
internal fun parseSheet(
    sheet:      Sheet,
    spec:       SheetSpec,
    cols:       List<ColumnDef>,
    taxYear:    String,
    properties: List<PropertyRef>,
    existing:   Map<Int, Map<String, Any?>>?,
    errors:     MutableList<String>,
): List<ParsedRow> {
    val result               = mutableListOf<ParsedRow>()
    val (startDate, endDate) = taxYearDateRange(taxYear)
    val start                = LocalDate.parse(startDate)
    val end                  = LocalDate.parse(endDate)

    val idIndex         = colIndex(cols, "id")
    val propertyIdIndex = cols.indexOfFirst { it.dbName == "property_id" }

    // Hidden columns are locked, so the user cannot blank them: they must be
    // excluded from the blank check that detects delete rows.
    val excludedForDelete = cols.indices.filter { cols[it].hidden }.toSet() + idIndex
    val firstRowOfId      = mutableMapOf<Int, Int>()

    for (rowIdx in 2..sheet.lastRowNum) {
        val row = sheet.getRow(rowIdx) ?: continue
        if (isBlankRow(row, cols.size)) continue

        val rowNum    = rowIdx + 1
        val rowErrors = mutableListOf<String>()
        fun err(msg: String) { rowErrors += "Sheet \"${sheet.sheetName}\" row $rowNum: $msg" }

        // id
        val idRaw = cellString(row, idIndex)
        val id: Int? = when {
            idRaw.isBlank() -> null
            else            -> idRaw.toIntOrNull()
                ?: run { err("ID \"$idRaw\" is not a valid whole number."); null }
        }
        if (id != null) {
            if (existing != null && id !in existing) {
                err(
                    "ID $id does not exist for tax year $taxYear, or has already been " +
                            "superseded or deleted.  The file cannot be imported."
                )
            }
            val earlier = firstRowOfId[id]
            if (earlier != null) err("ID $id is also used on row $earlier.  Each ID may appear only once.")
            else firstRowOfId[id] = rowNum
        }

        // delete row
        val isDelete = id != null && isBlankRow(row, cols.size, excludedForDelete)
        if (isDelete) {
            errors += rowErrors
            if (rowErrors.isEmpty()) result += ParsedRow(rowNum, id, true, emptyMap())
            continue
        }

        // new or edited row
        val values = mutableMapOf<String, Any?>()
        for ((ci, col) in cols.withIndex()) {
            if (col.dbName == "id") continue

            when (col.type) {
                ColumnType.INTEGER -> Unit   // property_id: handled with the PROPERTY column

                ColumnType.PROPERTY -> {
                    val pidFromCell = if (propertyIdIndex >= 0) cellString(row, propertyIdIndex).toIntOrNull() else null
                    val ref = resolveProperty(cellString(row, ci), pidFromCell, properties, spec.propertyType) { err(it) }
                    values["property_id"] = ref?.id
                    values["property"]    = ref?.display
                }

                ColumnType.CATEGORY -> {
                    val label  = cellString(row, ci)
                    val option = spec.categories.firstOrNull { it.label == label }
                    when {
                        label.isBlank() -> err("${col.friendlyName} must not be blank.")
                        option == null  -> err(
                            "${col.friendlyName} \"$label\" is not valid.  " +
                                    "Valid values: ${spec.categories.map { it.label }.sorted().joinToString()}"
                        )
                    }
                    values[col.dbName] = option?.dbKey
                }

                ColumnType.MONEY -> {
                    val raw = cellString(row, ci).replace(",", "").removePrefix("£").trim()
                    if (raw.isBlank()) {
                        if (col.required) err("${col.friendlyName} must not be blank.")
                        values[col.dbName] = null
                    } else {
                        val decimal = raw.toBigDecimalOrNull()
                        if (decimal == null) {
                            err("${col.friendlyName} \"$raw\" is not a valid number.")
                            values[col.dbName] = null
                        } else {
                            val value = decimal.toDouble()
                            // An unchanged amount is never rejected for its
                            // decimal places, so older data entered through
                            // the GUI (which does not limit them) can still
                            // be round-tripped.
                            val unchanged = id != null &&
                                    (existing?.get(id)?.get(col.dbName) as? Double) == value
                            when {
                                col.required && decimal.signum() <= 0 ->
                                    err("${col.friendlyName} must be greater than zero.")
                                !unchanged && decimal.stripTrailingZeros().scale() > 2 ->
                                    err("${col.friendlyName} \"$raw\" has more than two decimal places.")
                            }
                            values[col.dbName] = value
                        }
                    }
                }

                ColumnType.DATE -> {
                    val text = cellString(row, ci)
                    when {
                        text.isBlank() -> {
                            if (col.required) err("${col.friendlyName} must not be blank.")
                            values[col.dbName] = null
                        }
                        !isValidDate(text) -> {
                            err("${col.friendlyName} \"$text\" must be YYYY-MM-DD.")
                            values[col.dbName] = null
                        }
                        else -> {
                            val date = LocalDate.parse(text)
                            if (date < start || date > end) {
                                err(
                                    "${col.friendlyName} \"$text\" is outside tax year $taxYear " +
                                            "($startDate to $endDate).  " +
                                            "Check you have selected the correct tax year."
                                )
                            }
                            values[col.dbName] = date.toString()
                        }
                    }
                }

                ColumnType.BOOLEAN -> {
                    val text = cellString(row, ci)
                    when (text.trim().lowercase()) {
                        "yes", "true"  -> values[col.dbName] = true
                        "no", "false"  -> values[col.dbName] = false
                        "" -> {
                            // A blank, non-required Yes/No means No, as an
                            // unticked checkbox does in the GUI.
                            if (col.required) err("${col.friendlyName} must not be blank.")
                            values[col.dbName] = false
                        }
                        else -> {
                            err("${col.friendlyName} \"$text\" must be Yes or No.")
                            values[col.dbName] = null
                        }
                    }
                }

                ColumnType.COUNTRY_CODE -> {
                    val text = cellString(row, ci).uppercase()
                    when {
                        text.isBlank() -> err("${col.friendlyName} must not be blank.")
                        !COUNTRY_CODE_REGEX.matches(text) ->
                            err("${col.friendlyName} \"$text\" must be exactly three letters (for example FRA).")
                    }
                    values[col.dbName] = text
                }

                ColumnType.TEXT -> {
                    val text = cellString(row, ci)
                    if (col.required && text.isBlank()) err("${col.friendlyName} must not be blank.")
                    values[col.dbName] = text
                }
            }
        }

        errors += rowErrors
        if (rowErrors.isEmpty()) result += ParsedRow(rowNum, id, false, values)
    }

    return result
}

// ---------------------------------------------------------------------------
// Cell and row helpers
// ---------------------------------------------------------------------------

private fun colIndex(cols: List<ColumnDef>, dbName: String): Int =
    cols.indexOfFirst { it.dbName == dbName }
        .also { check(it >= 0) { "Column \"$dbName\" not found in column definitions" } }

/**
 * Returns the cell content as a plain string.
 *
 * Numeric cells with a date format become YYYY-MM-DD. Other numeric cells
 * are rendered as an integer if whole, otherwise as a plain decimal string.
 * Formula cells are read through their cached result.
 */
private fun cellString(row: Row, col: Int): String {
    val cell = row.getCell(col) ?: return ""
    val type = if (cell.cellType == CellType.FORMULA) cell.cachedFormulaResultType else cell.cellType
    return when (type) {
        CellType.NUMERIC ->
            if (DateUtil.isCellDateFormatted(cell)) {
                DateUtil.getLocalDateTime(cell.numericCellValue).toLocalDate().toString()
            } else {
                val d = cell.numericCellValue
                if (d == kotlin.math.floor(d)) d.toLong().toString()
                else d.toBigDecimal().toPlainString()
            }
        CellType.STRING  -> cell.stringCellValue.trim()
        CellType.BOOLEAN -> cell.booleanCellValue.toString()
        else             -> ""
    }
}

/**
 * True if every cell in columns 0 until [cols] is blank, optionally
 * skipping the indices in [excludeCols]. Only blank and empty-string cells
 * count as blank; numeric cells (the locked id columns) do not, so callers
 * checking for a user-blanked delete row must exclude those columns.
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
