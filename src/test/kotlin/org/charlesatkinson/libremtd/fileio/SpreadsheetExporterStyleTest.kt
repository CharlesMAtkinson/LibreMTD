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

package org.charlesatkinson.libremtd.fileio

import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.charlesatkinson.libremtd.fileio.components.ExportTable
import org.charlesatkinson.libremtd.fileio.components.sheetSpecFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Cell style and protection tests for the exported workbook. They build the
 * workbook straight from sheet data, so no database is involved.
 *
 * Columns of the UK property income sheet:
 *   0 id (locked), 1 property_id (hidden), 2 property, 3 date,
 *   4 category, 5 description, 6 amount
 */
class SpreadsheetExporterStyleTest {

    private val table = ExportTable.INCOME_PROPERTY_UK

    private fun sheetWithOneRow(): Sheet {
        val columns = sheetSpecFor(table).columns(listOf("1 High Street AB1 2CD"))
        val row = mapOf<String, Any?>(
            "id" to 1, "property_id" to 7, "property" to "1 High Street AB1 2CD",
            "transaction_date" to "2026-05-01", "category" to "Total rent received",
            "description" to "Rent", "amount" to 100.0,
        )
        return SpreadsheetExporter.buildWorkbook(mapOf(table to SheetData(columns, listOf(row)))).getSheet(table.label)
    }

    private fun XSSFCellStyle.rgbTail(): List<Byte> = fillForegroundXSSFColor.rgb.toList().takeLast(3)

    // ── colour ────────────────────────────────────────────────────────────

    @Test
    fun `headers of editable columns use the LibreMTD green`() {
        val sheet = sheetWithOneRow()

        for (ci in 2..6) {
            val style = sheet.getRow(0).getCell(ci).cellStyle as XSSFCellStyle
            assertEquals(listOf<Byte>(0x00, 0x70, 0x3c), style.rgbTail(), "column $ci header")
        }
    }

    @Test
    fun `headers of read-only columns are still grey, not green`() {
        val sheet = sheetWithOneRow()

        val idStyle = sheet.getRow(0).getCell(0).cellStyle as XSSFCellStyle
        val editableStyle = sheet.getRow(0).getCell(3).cellStyle as XSSFCellStyle

        assertNotEquals(editableStyle.rgbTail(), idStyle.rgbTail())
    }

    // ── the ID column is read only ────────────────────────────────────────

    @Test
    fun `the id cell of an exported row is locked`() {
        val sheet = sheetWithOneRow()

        assertTrue(sheet.getRow(2).getCell(0).cellStyle.locked)
    }

    @Test
    fun `the id column and the hidden property id column default to a locked style for new rows`() {
        val sheet = sheetWithOneRow()

        for (ci in 0..1) {
            val style = sheet.getColumnStyle(ci)
            assertNotNull(style, "column $ci should have a default style")
            assertTrue(style.locked, "column $ci should be locked")
        }
    }

    @Test
    fun `editable columns still default to an unlocked style for new rows`() {
        val sheet = sheetWithOneRow()

        for (ci in 2..6) assertFalse(sheet.getColumnStyle(ci).locked, "column $ci should be unlocked")
    }

    @Test
    fun `every cell style states explicitly that its protection setting applies`() {
        val sheet = sheetWithOneRow()

        val styles = listOf(
            sheet.getRow(0).getCell(0).cellStyle,   // read-only header
            sheet.getRow(0).getCell(3).cellStyle,   // editable header
            sheet.getRow(2).getCell(0).cellStyle,   // locked grey
            sheet.getRow(2).getCell(3).cellStyle,   // date
            sheet.getRow(2).getCell(5).cellStyle,   // text
            sheet.getRow(2).getCell(6).cellStyle,   // money
        )
        for (style in styles) {
            assertTrue((style as XSSFCellStyle).coreXf.applyProtection)
        }
    }

    @Test
    fun `the sheet is protected`() {
        assertTrue(sheetWithOneRow().protect)
    }
}
