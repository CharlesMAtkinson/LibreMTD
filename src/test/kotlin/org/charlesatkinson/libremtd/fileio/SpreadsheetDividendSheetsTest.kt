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

import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.charlesatkinson.libremtd.database.IncomeDividendForeignRepository
import org.charlesatkinson.libremtd.database.IncomeDividendRepository
import org.charlesatkinson.libremtd.database.SubmissionRepository
import org.charlesatkinson.libremtd.database.tables.IncomeDividendEntries
import org.charlesatkinson.libremtd.database.tables.IncomeDividendForeignEntries
import org.charlesatkinson.libremtd.database.tables.Periods
import org.charlesatkinson.libremtd.database.tables.Properties
import org.charlesatkinson.libremtd.database.tables.Submissions
import org.charlesatkinson.libremtd.database.tables.Users
import org.charlesatkinson.libremtd.fileio.components.ExportTable
import org.charlesatkinson.libremtd.ui.DividendCategory
import org.charlesatkinson.libremtd.ui.DividendForeignCategory
import org.charlesatkinson.libremtd.ui.DividendScalarCategory
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.deleteIfExists

/**
 * Export and import tests for the "Income (dividends, special)" sheet
 * (id, date, type, customer reference, gross amount) and the
 * "Income (dividends, foreign)" sheet (id, date, type, country,
 * amount before tax, tax taken off, SWT, FTCR, taxable).
 */
class SpreadsheetDividendSheetsTest {

    private val userId  = 1
    private val taxYear = "2026-27"

    private lateinit var db: Database
    private lateinit var dbFile: Path

    @BeforeEach
    fun setUp() {
        dbFile = Files.createTempFile("libremtd-test-", ".sqlite")
        db = Database.connect("jdbc:sqlite:${dbFile}", driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(
                Users, Periods, Submissions, Properties,
                IncomeDividendEntries, IncomeDividendForeignEntries,
            )
        }
    }

    @AfterEach
    fun tearDown() {
        transaction(db) {
            SchemaUtils.drop(
                IncomeDividendForeignEntries, IncomeDividendEntries,
                Properties, Submissions, Periods, Users,
            )
        }
        dbFile.deleteIfExists()
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun seedSpecial(
        category: DividendScalarCategory = DividendScalarCategory.StockDividend,
        amount: Double = 70.0, ref: String? = "REF1", date: String = "2026-05-02",
    ) = IncomeDividendRepository.recordDividend(userId, taxYear, category.dbKey, amount, ref, ref ?: "", date)

    private fun seedForeign(
        ftcr: Boolean = false, before: Double? = 100.0, tax: Double? = 10.0, swt: Double? = null,
        taxable: Double = 90.0, date: String = "2026-05-01",
    ) = IncomeDividendForeignRepository.record(
        userId, taxYear, DividendForeignCategory.ForeignDividend.dbKey, "FRA",
        before, tax, swt, ftcr, taxable, date,
    )

    private fun exportWorkbook(vararg tables: ExportTable): XSSFWorkbook =
        SpreadsheetExporter.buildWorkbook(tables.associateWith { sheetDataFor(it, userId, taxYear) })

    private fun XSSFWorkbook.sheetFor(table: ExportTable): Sheet = getSheet(table.label)

    private fun appendRow(sheet: Sheet, vararg cells: Any?) {
        val wb    = sheet.workbook
        val style = wb.createCellStyle().apply {
            dataFormat = wb.creationHelper.createDataFormat().getFormat("yyyy-mm-dd")
        }
        val row = sheet.createRow(maxOf(sheet.lastRowNum + 1, 2))
        cells.forEachIndexed { c, v ->
            when (v) {
                null         -> Unit
                is String    -> row.createCell(c).setCellValue(v)
                is Int       -> row.createCell(c).setCellValue(v.toDouble())
                is Double    -> row.createCell(c).setCellValue(v)
                is LocalDate -> row.createCell(c).also { it.setCellValue(v); it.cellStyle = style }
            }
        }
    }

    private fun import(wb: XSSFWorkbook): ImportResult =
        SpreadsheetImporter.importWorkbook(wb, userId, taxYear)

    private fun summaryOf(result: ImportResult): ImportSummary {
        assertTrue(result is ImportResult.Success, "Expected Success but got $result")
        return (result as ImportResult.Success).summary
    }

    private val june1 = LocalDate.of(2026, 6, 1)

    // ── layout ────────────────────────────────────────────────────────────

    @Test
    fun `the special sheet has the GUI column names and order`() {
        val sheet = exportWorkbook(ExportTable.INCOME_DIVIDENDS_SPECIAL).sheetFor(ExportTable.INCOME_DIVIDENDS_SPECIAL)

        assertEquals(
            listOf("ID", "Date", "Type", "Customer reference", "Gross amount (£)"),
            (0..4).map { sheet.getRow(0).getCell(it).stringCellValue },
        )
    }

    @Test
    fun `the foreign sheet has the GUI column names and order`() {
        val sheet = exportWorkbook(ExportTable.INCOME_DIVIDENDS_FOREIGN).sheetFor(ExportTable.INCOME_DIVIDENDS_FOREIGN)

        assertEquals(
            listOf(
                "ID", "Date", "Type", "Country", "Amt before tax (£)", "Tax taken off (£)",
                "SWT (£)", "FTCR", "Taxable (£)",
            ),
            (0..8).map { sheet.getRow(0).getCell(it).stringCellValue },
        )
    }

    @Test
    fun `the three dividend sheets follow each other, then property income`() {
        assertEquals(
            listOf(
                ExportTable.INCOME_DIVIDENDS, ExportTable.INCOME_DIVIDENDS_SPECIAL,
                ExportTable.INCOME_DIVIDENDS_FOREIGN, ExportTable.INCOME_PROPERTY_UK,
            ),
            ExportTable.entries.take(4),
        )
        assertTrue(ExportTable.entries.all { it.label.length <= 31 })
    }

    // ── export ────────────────────────────────────────────────────────────

    @Test
    fun `UK and special dividends are split between their sheets`() {
        IncomeDividendRepository.recordDividend(
            userId, taxYear, DividendCategory.UkDividends.dbKey, 50.0, null, "Company", "2026-05-01",
        )
        seedSpecial()

        val special = sheetDataFor(ExportTable.INCOME_DIVIDENDS_SPECIAL, userId, taxYear).rows
        val uk      = sheetDataFor(ExportTable.INCOME_DIVIDENDS, userId, taxYear).rows

        assertEquals(listOf(DividendScalarCategory.StockDividend.label), special.map { it["category"] })
        assertEquals(listOf(DividendCategory.UkDividends.label), uk.map { it["category"] })
        assertEquals("REF1", special.single()["customer_reference"])
    }

    @Test
    fun `a special row with no reference exports an empty reference`() {
        seedSpecial(ref = null)

        val row = sheetDataFor(ExportTable.INCOME_DIVIDENDS_SPECIAL, userId, taxYear).rows.single()

        assertEquals("", row["customer_reference"])
    }

    @Test
    fun `foreign FTCR is written as Yes or No and unset amounts are left blank`() {
        seedForeign(ftcr = true, swt = null, date = "2026-05-01")
        seedForeign(ftcr = false, swt = 5.0, date = "2026-05-02")
        val sheet = exportWorkbook(ExportTable.INCOME_DIVIDENDS_FOREIGN).sheetFor(ExportTable.INCOME_DIVIDENDS_FOREIGN)

        assertEquals("Yes", sheet.getRow(2).getCell(7).stringCellValue)
        assertEquals("No", sheet.getRow(3).getCell(7).stringCellValue)
        assertEquals(CellType.BLANK, sheet.getRow(2).getCell(6).cellType)
        assertEquals(5.0, sheet.getRow(3).getCell(6).numericCellValue)
        assertEquals(10.0, sheet.getRow(2).getCell(5).numericCellValue)
        assertEquals("FRA", sheet.getRow(2).getCell(3).stringCellValue)
        assertEquals(90.0, sheet.getRow(2).getCell(8).numericCellValue)
    }

    @Test
    fun `the FTCR column has a Yes or No dropdown`() {
        val sheet = exportWorkbook(ExportTable.INCOME_DIVIDENDS_FOREIGN).sheetFor(ExportTable.INCOME_DIVIDENDS_FOREIGN)

        val lists = sheet.dataValidations.map { it.validationConstraint.explicitListValues.toList() }

        assertTrue(lists.contains(listOf("Yes", "No")))
        assertTrue(lists.contains(DividendForeignCategory.entries.map { it.label }))
    }

    // ── import: special ───────────────────────────────────────────────────

    @Test
    fun `a new special row with a reference stores it as the customer reference and description`() {
        val wb = exportWorkbook(ExportTable.INCOME_DIVIDENDS_SPECIAL)
        appendRow(
            wb.sheetFor(ExportTable.INCOME_DIVIDENDS_SPECIAL),
            null, june1, DividendScalarCategory.RedeemableShares.label, "REF9", 120.0,
        )

        val summary = summaryOf(import(wb))

        assertEquals(1, summary.inserted)
        val entry = IncomeDividendRepository.currentDividendsForYear(userId, taxYear).single()
        assertEquals(DividendScalarCategory.RedeemableShares.dbKey, entry.category)
        assertEquals("REF9", entry.customerReference)
        assertEquals("REF9", entry.description)
        assertEquals(120.0, entry.amount)
    }

    @Test
    fun `a new special row with no reference stores null and an empty description`() {
        val wb = exportWorkbook(ExportTable.INCOME_DIVIDENDS_SPECIAL)
        appendRow(
            wb.sheetFor(ExportTable.INCOME_DIVIDENDS_SPECIAL),
            null, june1, DividendScalarCategory.StockDividend.label, null, 40.0,
        )

        summaryOf(import(wb))

        val entry = IncomeDividendRepository.currentDividendsForYear(userId, taxYear).single()
        assertNull(entry.customerReference)
        assertEquals("", entry.description)
    }

    @Test
    fun `a UK dividend type on the special sheet is rejected`() {
        val wb = exportWorkbook(ExportTable.INCOME_DIVIDENDS_SPECIAL)
        appendRow(
            wb.sheetFor(ExportTable.INCOME_DIVIDENDS_SPECIAL),
            null, june1, DividendCategory.UkDividends.label, "X", 40.0,
        )

        val result = import(wb)

        assertTrue(result is ImportResult.Failure)
        assertTrue((result as ImportResult.Failure).errors.single().contains("Type \"UK dividends\" is not valid"))
    }

    @Test
    fun `an edited special amount supersedes the old row`() {
        val original = seedSpecial(amount = 70.0)
        val wb = exportWorkbook(ExportTable.INCOME_DIVIDENDS_SPECIAL)
        wb.sheetFor(ExportTable.INCOME_DIVIDENDS_SPECIAL).getRow(2).getCell(4).setCellValue(75.0)

        val summary = summaryOf(import(wb))

        assertEquals(1, summary.superseded)
        val current = IncomeDividendRepository.currentDividendsForYear(userId, taxYear).single()
        assertEquals(75.0, current.amount)
        assertEquals("REF1", current.customerReference)
        assertTrue(current.id != original.id)
    }

    @Test
    fun `a blank-reference special row duplicating an existing one is reported as a conflict`() {
        seedSpecial(ref = null, date = "2026-05-02")
        val wb = exportWorkbook(ExportTable.INCOME_DIVIDENDS_SPECIAL)
        appendRow(
            wb.sheetFor(ExportTable.INCOME_DIVIDENDS_SPECIAL),
            null, LocalDate.of(2026, 5, 2), DividendScalarCategory.StockDividend.label, null, 70.0,
        )

        assertTrue(import(wb) is ImportResult.ConflictsFound)
    }

    // ── import: foreign ───────────────────────────────────────────────────

    @Test
    fun `a new foreign row is inserted, with a lower-case country upper-cased and blank FTCR meaning No`() {
        val wb = exportWorkbook(ExportTable.INCOME_DIVIDENDS_FOREIGN)
        appendRow(
            wb.sheetFor(ExportTable.INCOME_DIVIDENDS_FOREIGN),
            null, june1, DividendForeignCategory.DividendWhilstAbroad.label, "fra", null, null, null, null, 90.5,
        )

        val summary = summaryOf(import(wb))

        assertEquals(1, summary.inserted)
        val entry = IncomeDividendForeignRepository.currentEntriesForYear(userId, taxYear).single()
        assertEquals("FRA", entry.countryCode)
        assertEquals(DividendForeignCategory.DividendWhilstAbroad.dbKey, entry.category)
        assertNull(entry.amountBeforeTax)
        assertNull(entry.taxTakenOff)
        assertNull(entry.specialWithholdingTax)
        assertFalse(entry.foreignTaxCreditRelief)
        assertEquals(90.5, entry.taxableAmount)
    }

    @Test
    fun `a foreign row with all amounts and FTCR Yes is inserted in full`() {
        val wb = exportWorkbook(ExportTable.INCOME_DIVIDENDS_FOREIGN)
        appendRow(
            wb.sheetFor(ExportTable.INCOME_DIVIDENDS_FOREIGN),
            null, june1, DividendForeignCategory.ForeignDividend.label, "DEU", 200.0, 30.0, 5.0, "Yes", 165.0,
        )

        summaryOf(import(wb))

        val entry = IncomeDividendForeignRepository.currentEntriesForYear(userId, taxYear).single()
        assertEquals(200.0, entry.amountBeforeTax)
        assertEquals(30.0, entry.taxTakenOff)
        assertEquals(5.0, entry.specialWithholdingTax)
        assertTrue(entry.foreignTaxCreditRelief)
    }

    @Test
    fun `foreign validation errors are reported per row`() {
        val wb = exportWorkbook(ExportTable.INCOME_DIVIDENDS_FOREIGN)
        val sheet = wb.sheetFor(ExportTable.INCOME_DIVIDENDS_FOREIGN)
        val type = DividendForeignCategory.ForeignDividend.label
        appendRow(sheet, null, june1, type, "FR", null, null, null, null, 50.0)
        appendRow(sheet, null, june1, type, "FRA", null, null, null, null, 0.0)
        appendRow(sheet, null, june1, type, "FRA", null, null, null, "Maybe", 50.0)
        appendRow(sheet, null, june1, type, "FRA", "abc", null, null, null, 50.0)

        val result = import(wb)

        assertTrue(result is ImportResult.Failure)
        val text = (result as ImportResult.Failure).errors.joinToString("\n")
        assertEquals(4, result.errors.size, text)
        assertTrue(text.contains("Country \"FR\" must be exactly three letters"))
        assertTrue(text.contains("Taxable (£) must be greater than zero"))
        assertTrue(text.contains("FTCR \"Maybe\" must be Yes or No"))
        assertTrue(text.contains("Amt before tax (£) \"abc\" is not a valid number"))
        assertTrue(IncomeDividendForeignRepository.currentEntriesForYear(userId, taxYear).isEmpty())
    }

    @Test
    fun `a blank country is rejected`() {
        val wb = exportWorkbook(ExportTable.INCOME_DIVIDENDS_FOREIGN)
        appendRow(
            wb.sheetFor(ExportTable.INCOME_DIVIDENDS_FOREIGN),
            null, june1, DividendForeignCategory.ForeignDividend.label, null, null, null, null, null, 50.0,
        )

        val result = import(wb)

        assertTrue((result as ImportResult.Failure).errors.single().contains("Country must not be blank"))
    }

    @Test
    fun `changing FTCR from No to Yes supersedes the old row`() {
        val original = seedForeign(ftcr = false)
        val wb = exportWorkbook(ExportTable.INCOME_DIVIDENDS_FOREIGN)
        wb.sheetFor(ExportTable.INCOME_DIVIDENDS_FOREIGN).getRow(2).getCell(7).setCellValue("Yes")

        val summary = summaryOf(import(wb))

        assertEquals(1, summary.superseded)
        val current = IncomeDividendForeignRepository.currentEntriesForYear(userId, taxYear).single()
        assertTrue(current.foreignTaxCreditRelief)
        assertTrue(current.id != original.id)
    }

    @Test
    fun `clearing an optional amount on an existing foreign row changes it to unset`() {
        seedForeign(before = 100.0, tax = 10.0)
        val wb = exportWorkbook(ExportTable.INCOME_DIVIDENDS_FOREIGN)
        val row = wb.sheetFor(ExportTable.INCOME_DIVIDENDS_FOREIGN).getRow(2)
        row.removeCell(row.getCell(5))

        val summary = summaryOf(import(wb))

        assertEquals(1, summary.superseded)
        val current = IncomeDividendForeignRepository.currentEntriesForYear(userId, taxYear).single()
        assertNull(current.taxTakenOff)
        assertEquals(100.0, current.amountBeforeTax)
    }

    @Test
    fun `a foreign row with its editable cells removed is deleted`() {
        seedForeign()
        val wb = exportWorkbook(ExportTable.INCOME_DIVIDENDS_FOREIGN)
        val row = wb.sheetFor(ExportTable.INCOME_DIVIDENDS_FOREIGN).getRow(2)
        for (ci in 1..8) row.removeCell(row.getCell(ci))

        val summary = summaryOf(import(wb))

        assertEquals(1, summary.deleted)
        assertTrue(IncomeDividendForeignRepository.currentEntriesForYear(userId, taxYear).isEmpty())
    }

    // ── round trip, and the Final Declaration guard ───────────────────────

    @Test
    fun `exporting both sheets and importing them unchanged changes nothing`() {
        seedSpecial(ref = "REF1")
        seedSpecial(ref = null, category = DividendScalarCategory.BonusIssuesOfSecurities, date = "2026-05-03")
        seedForeign(ftcr = true, swt = 2.5)
        seedForeign(ftcr = false, before = null, tax = null, swt = null, taxable = 20.0, date = "2026-05-04")
        val wb = exportWorkbook(ExportTable.INCOME_DIVIDENDS_SPECIAL, ExportTable.INCOME_DIVIDENDS_FOREIGN)

        val summary = summaryOf(import(wb))

        assertEquals(0, summary.inserted)
        assertEquals(0, summary.superseded)
        assertEquals(0, summary.deleted)
        assertEquals(4, summary.unchanged)
    }

    @Test
    fun `a finally declared year is refused for the new sheets and nothing is written`() {
        val wb = exportWorkbook(ExportTable.INCOME_DIVIDENDS_FOREIGN)
        appendRow(
            wb.sheetFor(ExportTable.INCOME_DIVIDENDS_FOREIGN),
            null, june1, DividendForeignCategory.ForeignDividend.label, "FRA", null, null, null, null, 50.0,
        )
        SubmissionRepository.record(userId, null, taxYear, "final_declaration", "204")

        val result = import(wb)

        assertTrue(result is ImportResult.Failure)
        assertTrue((result as ImportResult.Failure).errors.single().contains("finally declared"))
        assertTrue(IncomeDividendForeignRepository.currentEntriesForYear(userId, taxYear).isEmpty())
    }
}
