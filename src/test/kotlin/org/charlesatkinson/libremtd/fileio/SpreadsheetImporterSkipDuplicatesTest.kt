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
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.charlesatkinson.libremtd.database.IncomeSavingsRepository
import org.charlesatkinson.libremtd.database.tables.IncomeSavingsEntries
import org.charlesatkinson.libremtd.database.tables.Periods
import org.charlesatkinson.libremtd.database.tables.Properties
import org.charlesatkinson.libremtd.database.tables.Submissions
import org.charlesatkinson.libremtd.database.tables.Users
import org.charlesatkinson.libremtd.fileio.components.ExportTable
import org.charlesatkinson.libremtd.ui.SavingsCategory
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.deleteIfExists

/**
 * Tests for the "skip duplicates" way of continuing after the importer has
 * reported rows which match existing records. The savings sheet is used
 * throughout, with its columns: id, date, category, description, amount.
 */
class SpreadsheetImporterSkipDuplicatesTest {

    private val userId  = 1
    private val taxYear = "2026-27"

    private lateinit var db: Database
    private lateinit var dbFile: Path

    @BeforeEach
    fun setUp() {
        dbFile = Files.createTempFile("libremtd-test-", ".sqlite")
        db = Database.connect("jdbc:sqlite:${dbFile}", driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(Users, Periods, Submissions, Properties, IncomeSavingsEntries)
        }
    }

    @AfterEach
    fun tearDown() {
        transaction(db) {
            SchemaUtils.drop(IncomeSavingsEntries, Properties, Submissions, Periods, Users)
        }
        dbFile.deleteIfExists()
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun seed(category: SavingsCategory, amount: Double, description: String, date: String) =
        IncomeSavingsRepository.recordSavingsIncome(userId, taxYear, category.dbKey, amount, description, date)

    private fun exportSavings(): Pair<XSSFWorkbook, Sheet> {
        val wb = SpreadsheetExporter.buildWorkbook(
            mapOf(ExportTable.INCOME_SAVINGS to sheetDataFor(ExportTable.INCOME_SAVINGS, userId, taxYear))
        )
        return wb to wb.getSheet(ExportTable.INCOME_SAVINGS.label)
    }

    private fun appendRow(sheet: Sheet, category: SavingsCategory, amount: Double, description: String, date: LocalDate) {
        val wb    = sheet.workbook
        val style = wb.createCellStyle().apply {
            dataFormat = wb.creationHelper.createDataFormat().getFormat("yyyy-mm-dd")
        }
        val row = sheet.createRow(maxOf(sheet.lastRowNum + 1, 2))
        row.createCell(1).also { it.setCellValue(date); it.cellStyle = style }
        row.createCell(2).setCellValue(category.label)
        row.createCell(3).setCellValue(description)
        row.createCell(4).setCellValue(amount)
    }

    private fun conflictsFor(wb: XSSFWorkbook): ImportResult.ConflictsFound {
        val result = SpreadsheetImporter.importWorkbook(wb, userId, taxYear)
        assertTrue(result is ImportResult.ConflictsFound, "Expected ConflictsFound but got $result")
        return result as ImportResult.ConflictsFound
    }

    private fun summaryOf(result: ImportResult): ImportSummary {
        assertTrue(result is ImportResult.Success, "Expected Success but got $result")
        return (result as ImportResult.Success).summary
    }

    private fun currentDescriptions() =
        IncomeSavingsRepository.currentForTaxYear(userId, taxYear).map { it.description }.toSet()

    // ── tests ─────────────────────────────────────────────────────────────

    @Test
    fun `skipping duplicates imports the other new rows and reports how many were skipped`() {
        seed(SavingsCategory.BankBuildingSociety, 50.0, "Interest", "2026-05-01")
        val (wb, sheet) = exportSavings()
        appendRow(sheet, SavingsCategory.BankBuildingSociety, 50.0, "Interest", LocalDate.of(2026, 5, 1))
        appendRow(sheet, SavingsCategory.Other, 7.5, "New", LocalDate.of(2026, 6, 1))

        val summary = summaryOf(conflictsFor(wb).proceedSkippingDuplicates())

        assertEquals(1, summary.inserted)
        assertEquals(1, summary.skipped)
        assertEquals(1, summary.unchanged)
        assertEquals(setOf("Interest", "New"), currentDescriptions())
        assertEquals(2, IncomeSavingsRepository.currentForTaxYear(userId, taxYear).size)
    }

    @Test
    fun `skipping when every new row is a duplicate writes nothing and still succeeds`() {
        seed(SavingsCategory.BankBuildingSociety, 50.0, "Interest", "2026-05-01")
        val (wb, sheet) = exportSavings()
        appendRow(sheet, SavingsCategory.BankBuildingSociety, 50.0, "Interest", LocalDate.of(2026, 5, 1))

        val summary = summaryOf(conflictsFor(wb).proceedSkippingDuplicates())

        assertEquals(0, summary.inserted)
        assertEquals(1, summary.skipped)
        assertEquals(1, IncomeSavingsRepository.currentForTaxYear(userId, taxYear).size)
    }

    @Test
    fun `skipping duplicates still applies edits made in the same file`() {
        seed(SavingsCategory.BankBuildingSociety, 50.0, "Interest", "2026-05-01")
        seed(SavingsCategory.Other, 20.0, "Other interest", "2026-05-02")
        val (wb, sheet) = exportSavings()
        sheet.getRow(2).getCell(4).setCellValue(55.0)     // edit the first row's amount
        appendRow(sheet, SavingsCategory.Other, 20.0, "Other interest", LocalDate.of(2026, 5, 2))

        val summary = summaryOf(conflictsFor(wb).proceedSkippingDuplicates())

        assertEquals(1, summary.superseded)
        assertEquals(1, summary.skipped)
        val amounts = IncomeSavingsRepository.currentForTaxYear(userId, taxYear).map { it.amount }.toSet()
        assertEquals(setOf(55.0, 20.0), amounts)
        assertEquals(2, IncomeSavingsRepository.currentForTaxYear(userId, taxYear).size)
    }

    @Test
    fun `inserting duplicates reports none as skipped`() {
        seed(SavingsCategory.BankBuildingSociety, 50.0, "Interest", "2026-05-01")
        val (wb, sheet) = exportSavings()
        appendRow(sheet, SavingsCategory.BankBuildingSociety, 50.0, "Interest", LocalDate.of(2026, 5, 1))

        val summary = summaryOf(conflictsFor(wb).proceed())

        assertEquals(1, summary.inserted)
        assertEquals(0, summary.skipped)
        assertEquals(2, IncomeSavingsRepository.currentForTaxYear(userId, taxYear).size)
    }

    @Test
    fun `an ordinary import reports none as skipped`() {
        val (wb, sheet) = exportSavings()
        appendRow(sheet, SavingsCategory.Other, 7.5, "New", LocalDate.of(2026, 6, 1))

        val summary = summaryOf(SpreadsheetImporter.importWorkbook(wb, userId, taxYear))

        assertEquals(1, summary.inserted)
        assertEquals(0, summary.skipped)
    }
}
