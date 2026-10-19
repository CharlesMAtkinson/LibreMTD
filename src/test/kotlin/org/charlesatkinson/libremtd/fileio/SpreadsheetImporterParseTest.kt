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

import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.charlesatkinson.libremtd.database.PropertyType
import org.charlesatkinson.libremtd.fileio.components.IncomeDividendSheetSpec
import org.charlesatkinson.libremtd.fileio.components.IncomePropertyForeignSheetSpec
import org.charlesatkinson.libremtd.fileio.components.IncomePropertyUkSheetSpec
import org.charlesatkinson.libremtd.fileio.components.SheetSpec
import org.charlesatkinson.libremtd.ui.DividendCategory
import org.charlesatkinson.libremtd.ui.DividendScalarCategory
import org.charlesatkinson.libremtd.ui.ForeignIncomeCategory
import org.charlesatkinson.libremtd.ui.IncomeCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Parse-level tests: an in-memory workbook goes in, parsed rows and error
 * messages come out. No database is involved.
 */
class SpreadsheetImporterParseTest {

    private val taxYear = "2026-27"
    private val spec: SheetSpec = IncomePropertyUkSheetSpec

    private val uk       = PropertyRef(1, propertyDisplayString("1 High Street", "AB1 2CD"), PropertyType.UK)
    private val secondUk = PropertyRef(3, propertyDisplayString("2 Low Street", "AB2 3DE"), PropertyType.UK)
    private val foreign  = PropertyRef(2, propertyDisplayString("10 Rue de Paris", null, "FRA"), PropertyType.FOREIGN)
    private val properties = listOf(uk, secondUk, foreign)

    private val category = IncomeCategory.TotalRentReceived
    private val date     = LocalDate.of(2026, 5, 1)

    // ── helpers ───────────────────────────────────────────────────────────

    private fun sheetWith(forSpec: SheetSpec, vararg rows: List<Any?>): Sheet {
        val wb    = XSSFWorkbook()
        val sheet = wb.createSheet(forSpec.table.label)
        val cols  = forSpec.columns(emptyList())

        sheet.createRow(0)
        val dbRow = sheet.createRow(1)
        cols.forEachIndexed { i, c -> dbRow.createCell(i).setCellValue(c.dbName) }

        val dateStyle = wb.createCellStyle().apply {
            dataFormat = wb.creationHelper.createDataFormat().getFormat("yyyy-mm-dd")
        }
        rows.forEachIndexed { r, cells ->
            val row = sheet.createRow(r + 2)
            cells.forEachIndexed { c, v ->
                when (v) {
                    null         -> Unit
                    is String    -> row.createCell(c).setCellValue(v)
                    is Int       -> row.createCell(c).setCellValue(v.toDouble())
                    is Double    -> row.createCell(c).setCellValue(v)
                    is LocalDate -> row.createCell(c).also { it.setCellValue(v); it.cellStyle = dateStyle }
                }
            }
        }
        return sheet
    }

    /** A property sheet row: id, property_id, property, date, category, description, amount. */
    private fun ukRow(
        id: Int? = null, propertyId: Int? = null, property: String? = uk.display,
        categoryLabel: String? = category.label, amount: Any? = 100.0,
        description: String? = "Rent", transactionDate: Any? = date,
    ): List<Any?> = listOf(id, propertyId, property, transactionDate, categoryLabel, description, amount)

    private fun parseWith(
        forSpec: SheetSpec, sheet: Sheet, existing: Map<Int, Map<String, Any?>>? = null,
    ): Pair<List<ParsedRow>, List<String>> {
        val errors = mutableListOf<String>()
        val rows = parseSheet(sheet, forSpec, forSpec.columns(emptyList()), taxYear, properties, existing, errors)
        return rows to errors
    }

    private fun parseUk(
        sheet: Sheet, existing: Map<Int, Map<String, Any?>>? = null,
    ): Pair<List<ParsedRow>, List<String>> = parseWith(spec, sheet, existing)

    // ── valid rows ────────────────────────────────────────────────────────

    @Test
    fun `a valid new row parses to typed values with the category as its db key`() {
        val (rows, errors) = parseUk(sheetWith(spec, ukRow()))

        assertTrue(errors.isEmpty(), errors.toString())
        val row = rows.single()
        assertNull(row.id)
        assertFalse(row.isDelete)
        assertEquals(uk.id, row.values["property_id"])
        assertEquals(category.dbKey, row.values["category"])
        assertEquals(100.0, row.values["amount"])
        assertEquals("Rent", row.values["description"])
        assertEquals("2026-05-01", row.values["transaction_date"])
    }

    @Test
    fun `completely blank rows are skipped silently`() {
        val (rows, errors) = parseUk(sheetWith(spec, ukRow(), emptyList(), ukRow(description = "Second")))

        assertTrue(errors.isEmpty())
        assertEquals(2, rows.size)
    }

    @Test
    fun `a row with only an id and the hidden property id is a delete`() {
        val (rows, errors) = parseUk(sheetWith(spec, listOf(5, 1)))

        assertTrue(errors.isEmpty())
        assertTrue(rows.single().isDelete)
        assertEquals(5, rows.single().id)
    }

    @Test
    fun `a row with an id and changed cells is an edit, not a delete`() {
        val (rows, _) = parseUk(sheetWith(spec, ukRow(id = 5, propertyId = 1)))

        assertEquals(5, rows.single().id)
        assertFalse(rows.single().isDelete)
    }

    // ── amount ────────────────────────────────────────────────────────────

    @Test
    fun `an amount of zero is rejected`() {
        val (rows, errors) = parseUk(sheetWith(spec, ukRow(amount = 0.0)))

        assertTrue(rows.isEmpty())
        assertTrue(errors.single().contains("Amount (£) must be greater than zero"))
    }

    @Test
    fun `a negative amount is rejected`() {
        val (_, errors) = parseUk(sheetWith(spec, ukRow(amount = -5.0)))

        assertTrue(errors.single().contains("must be greater than zero"))
    }

    @Test
    fun `a text amount is rejected as not a number`() {
        val (_, errors) = parseUk(sheetWith(spec, ukRow(amount = "abc")))

        assertTrue(errors.single().contains("\"abc\" is not a valid number"))
    }

    @Test
    fun `a blank amount is rejected`() {
        val (_, errors) = parseUk(sheetWith(spec, ukRow(amount = null)))

        assertTrue(errors.single().contains("Amount (£) must not be blank"))
    }

    @Test
    fun `an amount with more than two decimal places is rejected`() {
        val (_, errors) = parseUk(sheetWith(spec, ukRow(amount = 10.123)))

        assertTrue(errors.single().contains("more than two decimal places"))
    }

    @Test
    fun `an unchanged legacy amount with three decimal places is accepted`() {
        val existing = mapOf(5 to mapOf<String, Any?>("id" to 5, "amount" to 100.125))

        val (rows, errors) = parseUk(sheetWith(spec, ukRow(id = 5, propertyId = 1, amount = 100.125)), existing)

        assertTrue(errors.isEmpty(), errors.toString())
        assertEquals(100.125, rows.single().values["amount"])
    }

    @Test
    fun `a changed amount with three decimal places is still rejected on an existing row`() {
        val existing = mapOf(5 to mapOf<String, Any?>("id" to 5, "amount" to 100.125))

        val (_, errors) = parseUk(sheetWith(spec, ukRow(id = 5, propertyId = 1, amount = 100.126)), existing)

        assertTrue(errors.single().contains("more than two decimal places"))
    }

    @Test
    fun `a text amount with thousands separators and a pound sign is accepted`() {
        val (rows, errors) = parseUk(sheetWith(spec, ukRow(amount = "£1,250.50")))

        assertTrue(errors.isEmpty(), errors.toString())
        assertEquals(1250.5, rows.single().values["amount"])
    }

    // ── category, description, date ───────────────────────────────────────

    @Test
    fun `an unknown category is rejected and the valid ones listed`() {
        val (_, errors) = parseUk(sheetWith(spec, ukRow(categoryLabel = "Nonsense")))

        val message = errors.single()
        assertTrue(message.contains("\"Nonsense\" is not valid"))
        assertTrue(message.contains(IncomeCategory.OtherIncome.label))
    }

    @Test
    fun `a blank description is rejected`() {
        val (_, errors) = parseUk(sheetWith(spec, ukRow(description = null)))

        assertTrue(errors.single().contains("Description must not be blank"))
    }

    @Test
    fun `a date outside the tax year is rejected`() {
        val (_, errors) = parseUk(sheetWith(spec, ukRow(transactionDate = LocalDate.of(2027, 4, 6))))

        assertTrue(errors.single().contains("Date \"2027-04-06\" is outside tax year 2026-27"))
    }

    @Test
    fun `the first and last days of the tax year are accepted`() {
        val (rows, errors) = parseUk(
            sheetWith(
                spec,
                ukRow(transactionDate = LocalDate.of(2026, 4, 6)),
                ukRow(transactionDate = LocalDate.of(2027, 4, 5)),
            )
        )

        assertTrue(errors.isEmpty(), errors.toString())
        assertEquals(2, rows.size)
    }

    @Test
    fun `a text date in the wrong format is rejected`() {
        val (_, errors) = parseUk(sheetWith(spec, ukRow(transactionDate = "01/05/2026")))

        assertTrue(errors.single().contains("must be YYYY-MM-DD"))
    }

    @Test
    fun `a text date in the right format is accepted`() {
        val (rows, errors) = parseUk(sheetWith(spec, ukRow(transactionDate = "2026-05-01")))

        assertTrue(errors.isEmpty())
        assertEquals("2026-05-01", rows.single().values["transaction_date"])
    }

    // ── property ──────────────────────────────────────────────────────────

    @Test
    fun `a foreign property on the UK sheet gives a clear error`() {
        val (rows, errors) = parseUk(sheetWith(spec, ukRow(property = foreign.display)))

        assertTrue(rows.isEmpty())
        assertTrue(errors.single().contains("is a foreign property, but this sheet is for UK properties"))
    }

    @Test
    fun `a UK property on the foreign sheet gives a clear error`() {
        val foreignSpec: SheetSpec = IncomePropertyForeignSheetSpec
        val sheet = sheetWith(
            foreignSpec,
            listOf(null, null, uk.display, date, ForeignIncomeCategory.RentIncome.label, "Rent", 100.0),
        )

        val (rows, errors) = parseWith(foreignSpec, sheet)

        assertTrue(rows.isEmpty())
        assertTrue(errors.single().contains("is a UK property, but this sheet is for foreign properties"))
    }

    @Test
    fun `a foreign property on the foreign sheet parses with a foreign category`() {
        val foreignSpec: SheetSpec = IncomePropertyForeignSheetSpec
        val sheet = sheetWith(
            foreignSpec,
            listOf(null, null, foreign.display, date, ForeignIncomeCategory.RentIncome.label, "Rent", 900.0),
        )

        val (rows, errors) = parseWith(foreignSpec, sheet)

        assertTrue(errors.isEmpty(), errors.toString())
        assertEquals(foreign.id, rows.single().values["property_id"])
        assertEquals(ForeignIncomeCategory.RentIncome.dbKey, rows.single().values["category"])
    }

    @Test
    fun `a UK category on the foreign sheet is rejected`() {
        val foreignSpec: SheetSpec = IncomePropertyForeignSheetSpec
        val sheet = sheetWith(
            foreignSpec,
            listOf(null, null, foreign.display, date, IncomeCategory.TaxDeducted.label, "Tax", 5.0),
        )

        val (_, errors) = parseWith(foreignSpec, sheet)

        assertTrue(errors.single().contains("is not valid"))
    }

    @Test
    fun `a property whose address contains a comma resolves from its cell text`() {
        val commaProperty = PropertyRef(
            4, propertyDisplayString("42 Imaginary Crescent, Madeup-on-Sea", "MS2 7UP"), PropertyType.UK,
        )
        val errors = mutableListOf<String>()
        val sheet = sheetWith(spec, ukRow(property = "42 Imaginary Crescent, Madeup-on-Sea MS2 7UP"))

        val rows = parseSheet(sheet, spec, spec.columns(emptyList()), taxYear, listOf(commaProperty), null, errors)

        assertTrue(errors.isEmpty(), errors.toString())
        assertEquals(4, rows.single().values["property_id"])
    }

    @Test
    fun `an unknown property is rejected`() {
        val (_, errors) = parseUk(sheetWith(spec, ukRow(property = "99 Nowhere Lane ZZ9 9ZZ")))

        assertTrue(errors.single().contains("not found"))
    }

    @Test
    fun `a blank property with no property id is rejected`() {
        val (_, errors) = parseUk(sheetWith(spec, ukRow(property = null)))

        assertTrue(errors.single().contains("Property must not be blank"))
    }

    @Test
    fun `a blank property falls back to the hidden property id`() {
        val (rows, errors) = parseUk(sheetWith(spec, ukRow(propertyId = uk.id, property = null)))

        assertTrue(errors.isEmpty(), errors.toString())
        assertEquals(uk.id, rows.single().values["property_id"])
    }

    @Test
    fun `changing the dropdown on an existing row wins over the hidden property id`() {
        val (rows, errors) = parseUk(sheetWith(spec, ukRow(id = 5, propertyId = uk.id, property = secondUk.display)))

        assertTrue(errors.isEmpty(), errors.toString())
        assertEquals(secondUk.id, rows.single().values["property_id"])
    }

    // ── ids ───────────────────────────────────────────────────────────────

    @Test
    fun `an id that is not in the existing rows is rejected, including on delete rows`() {
        val existing = mapOf(5 to mapOf<String, Any?>("id" to 5, "amount" to 100.0))

        val (rows, errors) = parseUk(sheetWith(spec, listOf(99, 1), ukRow(id = 98, propertyId = 1)), existing)

        assertTrue(rows.isEmpty())
        assertEquals(2, errors.size)
        assertTrue(errors.all { it.contains("does not exist for tax year 2026-27") })
    }

    @Test
    fun `the same id on two rows is rejected`() {
        val existing = mapOf(5 to mapOf<String, Any?>("id" to 5, "amount" to 100.0))

        val (_, errors) = parseUk(sheetWith(spec, ukRow(id = 5, propertyId = 1), ukRow(id = 5, propertyId = 1)), existing)

        assertTrue(errors.single().contains("also used on row 3"))
    }

    @Test
    fun `a non-numeric id is rejected`() {
        val (_, errors) = parseUk(sheetWith(spec, ukRow(id = null).toMutableList().also { it[0] = "abc" }))

        assertTrue(errors.single().contains("not a valid whole number"))
    }

    // ── error reporting ───────────────────────────────────────────────────

    @Test
    fun `errors name the sheet and the spreadsheet row number`() {
        val (_, errors) = parseUk(sheetWith(spec, ukRow(), ukRow(amount = 0.0)))

        assertTrue(errors.single().startsWith("Sheet \"Income (property, UK)\" row 4:"))
    }

    @Test
    fun `a bad row is left out but good rows are still parsed`() {
        val (rows, errors) = parseUk(sheetWith(spec, ukRow(description = "good"), ukRow(amount = 0.0)))

        assertEquals(1, errors.size)
        assertEquals(listOf("good"), rows.map { it.values["description"] })
    }

    // ── headers ───────────────────────────────────────────────────────────

    @Test
    fun `validateHeaders accepts the expected db name row`() {
        val errors = mutableListOf<String>()

        validateHeaders(sheetWith(spec), spec.columns(emptyList()), errors)

        assertTrue(errors.isEmpty())
    }

    @Test
    fun `validateHeaders reports a changed column name`() {
        val sheet = sheetWith(spec)
        sheet.getRow(1).getCell(3).setCellValue("wrong")
        val errors = mutableListOf<String>()

        validateHeaders(sheet, spec.columns(emptyList()), errors)

        assertTrue(errors.single().contains("expected db name \"transaction_date\", found \"wrong\""))
    }

    @Test
    fun `validateHeaders rejects a file in the old column order`() {
        // The pre-change layout was id, property_id, property, category, amount, description, date.
        val sheet = sheetWith(spec)
        listOf("id", "property_id", "property", "category", "amount", "description", "transaction_date")
            .forEachIndexed { i, name -> sheet.getRow(1).getCell(i).setCellValue(name) }
        val errors = mutableListOf<String>()

        validateHeaders(sheet, spec.columns(emptyList()), errors)

        assertTrue(errors.isNotEmpty())
        assertTrue(errors.first().contains("Do not add, remove, or reorder columns"))
    }

    @Test
    fun `validateHeaders reports a missing db name row`() {
        val sheet = XSSFWorkbook().createSheet("x")
        val errors = mutableListOf<String>()

        validateHeaders(sheet, spec.columns(emptyList()), errors)

        assertTrue(errors.single().contains("missing header rows"))
    }

    // ── a sheet without a property column ─────────────────────────────────

    @Test
    fun `the dividends sheet parses UK categories`() {
        val dividendSpec: SheetSpec = IncomeDividendSheetSpec
        val sheet = sheetWith(
            dividendSpec, listOf(null, date, DividendCategory.UkDividends.label, "Company", 50.0),
        )

        val (rows, errors) = parseWith(dividendSpec, sheet)

        assertTrue(errors.isEmpty(), errors.toString())
        assertEquals(DividendCategory.UkDividends.dbKey, rows.single().values["category"])
        assertFalse(rows.single().values.containsKey("property_id"))
    }

    @Test
    fun `the dividends sheet rejects a special type category, naming the Type column`() {
        val dividendSpec: SheetSpec = IncomeDividendSheetSpec
        val sheet = sheetWith(
            dividendSpec, listOf(null, date, DividendScalarCategory.StockDividend.label, "Shares", 50.0),
        )

        val (_, errors) = parseWith(dividendSpec, sheet)

        assertTrue(errors.single().contains("Type \"Stock dividend\" is not valid"))
    }
}
