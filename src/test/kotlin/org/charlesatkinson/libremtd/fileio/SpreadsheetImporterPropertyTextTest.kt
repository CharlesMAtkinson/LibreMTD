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

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.charlesatkinson.libremtd.database.PropertyType
import org.charlesatkinson.libremtd.fileio.components.IncomePropertyUkSheetSpec
import org.charlesatkinson.libremtd.fileio.components.SheetSpec
import org.charlesatkinson.libremtd.ui.IncomeCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Parse-level tests for how the property cell is interpreted: the default
 * for a sole property, and the "Did you mean" hint when a number inside the
 * address differs from a real property's. No database is involved.
 */
class SpreadsheetImporterPropertyTextTest {

    private val taxYear = "2026-27"
    private val spec: SheetSpec = IncomePropertyUkSheetSpec

    private val high    = PropertyRef(1, propertyDisplayString("1 High Street", "AB1 2CD"), PropertyType.UK)
    private val low     = PropertyRef(3, propertyDisplayString("2 Low Street", "AB2 3DE"), PropertyType.UK)
    private val foreign = PropertyRef(2, propertyDisplayString("10 Rue de Paris", null, "FRA"), PropertyType.FOREIGN)

    /** Parses one new UK income row whose property cell holds [propertyCell] (null leaves it empty). */
    private fun parse(propertyCell: String?, properties: List<PropertyRef>): Pair<List<ParsedRow>, List<String>> {
        val wb    = XSSFWorkbook()
        val sheet = wb.createSheet(spec.table.label)
        val cols  = spec.columns(emptyList())

        sheet.createRow(0)
        val dbRow = sheet.createRow(1)
        cols.forEachIndexed { i, c -> dbRow.createCell(i).setCellValue(c.dbName) }

        // Columns: id, property_id, property, date, category, description, amount
        val dateStyle = wb.createCellStyle().apply {
            dataFormat = wb.creationHelper.createDataFormat().getFormat("yyyy-mm-dd")
        }
        val row = sheet.createRow(2)
        if (propertyCell != null) row.createCell(2).setCellValue(propertyCell)
        row.createCell(3).also { it.setCellValue(LocalDate.of(2026, 5, 1)); it.cellStyle = dateStyle }
        row.createCell(4).setCellValue(IncomeCategory.TotalRentReceived.label)
        row.createCell(5).setCellValue("Rent")
        row.createCell(6).setCellValue(100.0)

        val errors = mutableListOf<String>()
        val rows = parseSheet(sheet, spec, cols, taxYear, properties, null, errors)
        return rows to errors
    }

    // ── a blank property cell ─────────────────────────────────────────────

    @Test
    fun `a blank property uses the only property of the sheet's type`() {
        val (rows, errors) = parse(null, listOf(high, foreign))

        assertTrue(errors.isEmpty(), errors.toString())
        assertEquals(high.id, rows.single().values["property_id"])
        assertEquals(high.display, rows.single().values["property"])
    }

    @Test
    fun `a blank property is rejected when there are several properties of the type`() {
        val (rows, errors) = parse(null, listOf(high, low, foreign))

        assertTrue(rows.isEmpty())
        assertTrue(errors.single().contains("Property must not be blank"))
    }

    @Test
    fun `a blank property is rejected when there is no property of the type`() {
        val (rows, errors) = parse(null, listOf(foreign))

        assertTrue(rows.isEmpty())
        assertTrue(errors.single().contains("Property must not be blank"))
    }

    // ── a property cell with a different number ───────────────────────────

    @Test
    fun `a property whose number differs is rejected with a suggestion`() {
        val (rows, errors) = parse("2 High Street AB1 2CD", listOf(high, low))

        assertTrue(rows.isEmpty())
        val message = errors.single()
        assertTrue(message.contains("not found"))
        assertTrue(message.contains("Did you mean \"1 High Street AB1 2CD\"?"))
        assertFalse(message.contains("fill handle"))
    }

    @Test
    fun `no suggestion is made when two properties differ only by digits`() {
        val first  = PropertyRef(1, propertyDisplayString("1 High Street", "AB1 2CD"), PropertyType.UK)
        val second = PropertyRef(2, propertyDisplayString("2 High Street", "AB1 2CD"), PropertyType.UK)

        val (_, errors) = parse("3 High Street AB1 2CD", listOf(first, second))

        assertTrue(errors.single().contains("not found"))
        assertFalse(errors.single().contains("Did you mean"))
    }

    @Test
    fun `no suggestion is made when nothing is similar`() {
        val (_, errors) = parse("99 Nowhere Lane ZZ9 9ZZ", listOf(high, low))

        assertTrue(errors.single().contains("not found"))
        assertFalse(errors.single().contains("Did you mean"))
    }

    @Test
    fun `a similar property of the wrong type is not suggested`() {
        val foreignSimilar = PropertyRef(5, propertyDisplayString("1 High Street", null, "FRA"), PropertyType.FOREIGN)

        val (_, errors) = parse("2 High Street (FRA)", listOf(high, low, foreignSimilar))

        assertFalse(errors.single().contains("Did you mean"))
    }

    @Test
    fun `an exact match is never second-guessed`() {
        val (rows, errors) = parse(low.display, listOf(high, low))

        assertTrue(errors.isEmpty(), errors.toString())
        assertEquals(low.id, rows.single().values["property_id"])
    }
}
