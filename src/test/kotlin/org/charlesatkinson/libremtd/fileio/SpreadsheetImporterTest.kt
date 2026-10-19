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
import org.charlesatkinson.libremtd.database.ExpensePropertyForeignRepository
import org.charlesatkinson.libremtd.database.ExpensePropertyUkRepository
import org.charlesatkinson.libremtd.database.IncomeDividendForeignRepository
import org.charlesatkinson.libremtd.database.IncomeDividendRepository
import org.charlesatkinson.libremtd.database.IncomePropertyForeignRepository
import org.charlesatkinson.libremtd.database.IncomePropertyUkRepository
import org.charlesatkinson.libremtd.database.IncomeSavingsRepository
import org.charlesatkinson.libremtd.database.PeriodRepository
import org.charlesatkinson.libremtd.database.Property
import org.charlesatkinson.libremtd.database.PropertyRepository
import org.charlesatkinson.libremtd.database.PropertyType
import org.charlesatkinson.libremtd.database.SubmissionRepository
import org.charlesatkinson.libremtd.database.tables.ExpensePropertyForeignEntries
import org.charlesatkinson.libremtd.database.tables.ExpensePropertyUkEntries
import org.charlesatkinson.libremtd.database.tables.IncomeDividendEntries
import org.charlesatkinson.libremtd.database.tables.IncomeDividendForeignEntries
import org.charlesatkinson.libremtd.database.tables.IncomePropertyForeignEntries
import org.charlesatkinson.libremtd.database.tables.IncomePropertyUkEntries
import org.charlesatkinson.libremtd.database.tables.IncomeSavingsEntries
import org.charlesatkinson.libremtd.database.tables.Periods
import org.charlesatkinson.libremtd.database.tables.Properties
import org.charlesatkinson.libremtd.database.tables.Submissions
import org.charlesatkinson.libremtd.database.tables.Users
import org.charlesatkinson.libremtd.fileio.components.ExportTable
import org.charlesatkinson.libremtd.fileio.components.IncomePropertyUkSheetSpec
import org.charlesatkinson.libremtd.fileio.components.IncomeSavingsSheetSpec
import org.charlesatkinson.libremtd.fileio.components.SheetSpec
import org.charlesatkinson.libremtd.ui.DividendCategory
import org.charlesatkinson.libremtd.ui.DividendForeignCategory
import org.charlesatkinson.libremtd.ui.DividendScalarCategory
import org.charlesatkinson.libremtd.ui.ExpenseCategory
import org.charlesatkinson.libremtd.ui.ForeignExpenseCategory
import org.charlesatkinson.libremtd.ui.ForeignIncomeCategory
import org.charlesatkinson.libremtd.ui.IncomeCategory
import org.charlesatkinson.libremtd.ui.SavingsCategory
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.deleteIfExists

/**
 * Database-level import tests. Workbooks are built in memory by the real
 * exporter, edited cell by cell like a user would, and imported through
 * importWorkbook. One test round-trips through an actual file.
 *
 * Column layouts used by appendRow:
 *   property sheets: id, property_id, property, date, category, description, amount
 *   dividends and savings: id, date, category, description, amount
 */
class SpreadsheetImporterTest {

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
                IncomePropertyUkEntries, ExpensePropertyUkEntries,
                IncomePropertyForeignEntries, ExpensePropertyForeignEntries,
                IncomeDividendEntries, IncomeDividendForeignEntries, IncomeSavingsEntries,
            )
        }
    }

    @AfterEach
    fun tearDown() {
        transaction(db) {
            SchemaUtils.drop(
                IncomeSavingsEntries, IncomeDividendForeignEntries, IncomeDividendEntries,
                ExpensePropertyForeignEntries, IncomePropertyForeignEntries,
                ExpensePropertyUkEntries, IncomePropertyUkEntries,
                Properties, Submissions, Periods, Users,
            )
        }
        dbFile.deleteIfExists()
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun ukProperty(address: String = "1 High Street", postcode: String = "AB1 2CD") =
        PropertyRepository.create(
            userId = userId, address = address, propertyType = PropertyType.UK, postcode = postcode,
        )

    private fun foreignProperty(address: String = "10 Rue de Paris") =
        PropertyRepository.create(
            userId = userId, address = address, propertyType = PropertyType.FOREIGN, countryCode = "FRA",
        )

    private fun display(p: Property) = propertyDisplayString(p.address, p.postcode, p.countryCode)

    private fun periodId(date: String) = PeriodRepository.getOrCreateStandard(date).id

    private fun seedIncome(
        property: Property, date: String = "2026-05-01", amount: Double = 100.0, description: String = "Rent",
    ) = IncomePropertyUkRepository.recordPropertyIncome(
        periodId(date), userId, property.id, IncomeCategory.TotalRentReceived.dbKey, amount, description, date,
    )

    private fun seedSavings(
        forUser: Int = userId, date: String = "2026-05-01", amount: Double = 50.0, description: String = "Interest",
    ) = IncomeSavingsRepository.recordSavingsIncome(
        forUser, taxYearFor(date), SavingsCategory.BankBuildingSociety.dbKey, amount, description, date,
    )

    private fun taxYearFor(date: String) = org.charlesatkinson.libremtd.database.taxYearForDate(date)

    private fun exportWorkbook(vararg tables: ExportTable): XSSFWorkbook =
        SpreadsheetExporter.buildWorkbook(tables.associateWith { sheetDataFor(it, userId, taxYear) })

    private fun XSSFWorkbook.sheetFor(table: ExportTable): Sheet = getSheet(table.label)

    /** Appends a row below the last one, writing each value by its type. */
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

    private fun import(wb: XSSFWorkbook, specs: List<SheetSpec>? = null): ImportResult =
        if (specs == null) SpreadsheetImporter.importWorkbook(wb, userId, taxYear)
        else SpreadsheetImporter.importWorkbook(wb, userId, taxYear, specs)

    private fun summaryOf(result: ImportResult): ImportSummary {
        assertTrue(result is ImportResult.Success, "Expected Success but got $result")
        return (result as ImportResult.Success).summary
    }

    private fun declareFinal() {
        SubmissionRepository.record(
            userId = userId, periodId = null, taxYear = taxYear,
            submissionType = "final_declaration", hmrcResponse = "204",
        )
    }

    // ── inserting new rows ────────────────────────────────────────────────

    @Test
    fun `a new UK income row is inserted through the repository and gets a standard period`() {
        val property = ukProperty()
        val wb = exportWorkbook(ExportTable.INCOME_PROPERTY_UK)
        appendRow(
            wb.sheetFor(ExportTable.INCOME_PROPERTY_UK),
            null, null, display(property), LocalDate.of(2026, 6, 1),
            IncomeCategory.TotalRentReceived.label, "June rent", 123.45,
        )

        val summary = summaryOf(import(wb))

        assertEquals(1, summary.inserted)
        val entry = IncomePropertyUkRepository.currentPropertyIncomeForYear(userId, taxYear).single()
        assertEquals(property.id, entry.propertyId)
        assertEquals(IncomeCategory.TotalRentReceived.dbKey, entry.category)
        assertEquals(123.45, entry.amount)
        assertEquals("June rent", entry.description)
        assertEquals("2026-06-01", entry.transactionDate)
        assertEquals("#001", PeriodRepository.findById(entry.periodId)?.periodKey)
    }

    @Test
    fun `a new expenses row is inserted with its property`() {
        val property = ukProperty()
        val wb = exportWorkbook(ExportTable.EXPENSES_PROPERTY_UK)
        appendRow(
            wb.sheetFor(ExportTable.EXPENSES_PROPERTY_UK),
            null, null, display(property), LocalDate.of(2026, 8, 10),
            ExpenseCategory.RepairsAndMaintenance.label, "Boiler service", 80.0,
        )

        summaryOf(import(wb))

        val entry = ExpensePropertyUkRepository.currentPropertyExpensesForYear(userId, taxYear).single()
        assertEquals(property.id, entry.propertyId)
        assertEquals("#002", PeriodRepository.findById(entry.periodId)?.periodKey)
    }

    @Test
    fun `a new foreign income row is inserted against a foreign property`() {
        val property = foreignProperty()
        val wb = exportWorkbook(ExportTable.INCOME_PROPERTY_FOREIGN)
        appendRow(
            wb.sheetFor(ExportTable.INCOME_PROPERTY_FOREIGN),
            null, null, display(property), LocalDate.of(2026, 6, 1),
            ForeignIncomeCategory.RentIncome.label, "June rent", 900.0,
        )

        val summary = summaryOf(import(wb))

        assertEquals(1, summary.inserted)
        val entry = IncomePropertyForeignRepository.currentForeignPropertyIncomeForYear(userId, taxYear).single()
        assertEquals(property.id, entry.propertyId)
        assertEquals(ForeignIncomeCategory.RentIncome.dbKey, entry.category)
        assertEquals(900.0, entry.amount)
        assertEquals("#001", PeriodRepository.findById(entry.periodId)?.periodKey)
    }

    @Test
    fun `a new foreign expenses row is inserted against a foreign property`() {
        val property = foreignProperty()
        val wb = exportWorkbook(ExportTable.EXPENSES_PROPERTY_FOREIGN)
        appendRow(
            wb.sheetFor(ExportTable.EXPENSES_PROPERTY_FOREIGN),
            null, null, display(property), LocalDate.of(2026, 9, 10),
            ForeignExpenseCategory.RepairsAndMaintenance.label, "Roof", 300.0,
        )

        summaryOf(import(wb))

        val entry = ExpensePropertyForeignRepository.currentForeignPropertyExpensesForYear(userId, taxYear).single()
        assertEquals(property.id, entry.propertyId)
        assertEquals(ForeignExpenseCategory.RepairsAndMaintenance.dbKey, entry.category)
        assertEquals("#002", PeriodRepository.findById(entry.periodId)?.periodKey)
    }

    @Test
    fun `a UK property on a foreign sheet is rejected and nothing is written`() {
        val uk = ukProperty()
        val wb = exportWorkbook(ExportTable.INCOME_PROPERTY_FOREIGN)
        appendRow(
            wb.sheetFor(ExportTable.INCOME_PROPERTY_FOREIGN),
            null, null, display(uk), LocalDate.of(2026, 6, 1),
            ForeignIncomeCategory.RentIncome.label, "Wrong sheet", 900.0,
        )

        val result = import(wb)

        assertTrue(result is ImportResult.Failure)
        assertTrue((result as ImportResult.Failure).errors.single().contains("is a UK property"))
        assertTrue(IncomePropertyForeignRepository.currentForeignPropertyIncomeForYear(userId, taxYear).isEmpty())
    }

    @Test
    fun `new dividend and savings rows are inserted`() {
        val wb = exportWorkbook(ExportTable.INCOME_DIVIDENDS, ExportTable.INCOME_SAVINGS)
        appendRow(
            wb.sheetFor(ExportTable.INCOME_DIVIDENDS),
            null, LocalDate.of(2026, 7, 1), DividendCategory.UkDividends.label, "Company dividend", 60.0,
        )
        appendRow(
            wb.sheetFor(ExportTable.INCOME_SAVINGS),
            null, LocalDate.of(2026, 7, 2), SavingsCategory.BankBuildingSociety.label, "Interest", 12.5,
        )

        val summary = summaryOf(import(wb))

        assertEquals(2, summary.inserted)
        assertEquals(60.0, IncomeDividendRepository.currentDividendsForYear(userId, taxYear).single().amount)
        assertEquals(12.5, IncomeSavingsRepository.currentForTaxYear(userId, taxYear).single().amount)
    }

    // ── editing, deleting, leaving unchanged ──────────────────────────────

    @Test
    fun `an edited row supersedes the old row and inserts the new one`() {
        val property = ukProperty()
        val original = seedIncome(property, amount = 100.0)
        val wb = exportWorkbook(ExportTable.INCOME_PROPERTY_UK)
        wb.sheetFor(ExportTable.INCOME_PROPERTY_UK).getRow(2).getCell(6).setCellValue(250.0)

        val summary = summaryOf(import(wb))

        assertEquals(1, summary.superseded)
        assertEquals(1, summary.inserted)
        val current = IncomePropertyUkRepository.currentPropertyIncomeForYear(userId, taxYear).single()
        assertEquals(250.0, current.amount)
        assertTrue(current.id != original.id)
        assertEquals(2, IncomePropertyUkRepository.historyForPeriod(original.periodId).size)
    }

    @Test
    fun `changing only the property dropdown moves the entry to the other property`() {
        val first  = ukProperty("1 High Street", "AB1 2CD")
        val second = ukProperty("2 Low Street", "AB2 3DE")
        seedIncome(first)
        val wb = exportWorkbook(ExportTable.INCOME_PROPERTY_UK)
        wb.sheetFor(ExportTable.INCOME_PROPERTY_UK).getRow(2).getCell(2).setCellValue(display(second))

        summaryOf(import(wb))

        val current = IncomePropertyUkRepository.currentPropertyIncomeForYear(userId, taxYear).single()
        assertEquals(second.id, current.propertyId)
    }

    @Test
    fun `an unchanged export creates no history`() {
        val property = ukProperty()
        val entry = seedIncome(property)
        val wb = exportWorkbook(ExportTable.INCOME_PROPERTY_UK)

        val summary = summaryOf(import(wb))

        assertEquals(0, summary.inserted)
        assertEquals(0, summary.superseded)
        assertEquals(1, summary.unchanged)
        assertEquals(1, IncomePropertyUkRepository.historyForPeriod(entry.periodId).size)
    }

    @Test
    fun `a row with its editable cells removed is deleted`() {
        val property = ukProperty()
        val entry = seedIncome(property)
        val wb = exportWorkbook(ExportTable.INCOME_PROPERTY_UK)
        val row = wb.sheetFor(ExportTable.INCOME_PROPERTY_UK).getRow(2)
        for (ci in 2..6) row.removeCell(row.getCell(ci))

        val summary = summaryOf(import(wb))

        assertEquals(1, summary.deleted)
        assertTrue(IncomePropertyUkRepository.currentPropertyIncomeForYear(userId, taxYear).isEmpty())
        assertNotNull(IncomePropertyUkRepository.historyForPeriod(entry.periodId).single().supersededAt)
    }

    @Test
    fun `a deleted savings row is superseded without a replacement`() {
        seedSavings()
        val wb = exportWorkbook(ExportTable.INCOME_SAVINGS)
        val row = wb.sheetFor(ExportTable.INCOME_SAVINGS).getRow(2)
        for (ci in 1..4) row.removeCell(row.getCell(ci))

        val summary = summaryOf(import(wb))

        assertEquals(1, summary.deleted)
        assertTrue(IncomeSavingsRepository.currentForTaxYear(userId, taxYear).isEmpty())
    }

    @Test
    fun `an edited foreign income row is superseded and replaced`() {
        val property = foreignProperty()
        val original = IncomePropertyForeignRepository.recordForeignPropertyIncome(
            periodId("2026-05-01"), userId, property.id, ForeignIncomeCategory.RentIncome.dbKey,
            900.0, "May rent", "2026-05-01",
        )
        val wb = exportWorkbook(ExportTable.INCOME_PROPERTY_FOREIGN)
        wb.sheetFor(ExportTable.INCOME_PROPERTY_FOREIGN).getRow(2).getCell(6).setCellValue(950.0)

        val summary = summaryOf(import(wb))

        assertEquals(1, summary.superseded)
        val current = IncomePropertyForeignRepository.currentForeignPropertyIncomeForYear(userId, taxYear).single()
        assertEquals(950.0, current.amount)
        assertTrue(current.id != original.id)
    }

    // ── ids ───────────────────────────────────────────────────────────────

    @Test
    fun `an id belonging to another user is rejected and that user's entry is untouched`() {
        val theirs = seedSavings(forUser = 2)
        val wb = exportWorkbook(ExportTable.INCOME_SAVINGS)
        appendRow(
            wb.sheetFor(ExportTable.INCOME_SAVINGS),
            theirs.id, LocalDate.of(2026, 5, 1), SavingsCategory.Other.label, "Hijack", 999.0,
        )

        val result = import(wb)

        assertTrue(result is ImportResult.Failure)
        assertTrue((result as ImportResult.Failure).errors.single().contains("does not exist"))
        assertEquals(50.0, IncomeSavingsRepository.currentForTaxYear(2, taxYear).single().amount)
    }

    @Test
    fun `an id from a different tax year is rejected`() {
        val nextYear = seedSavings(date = "2027-05-01")
        val wb = exportWorkbook(ExportTable.INCOME_SAVINGS)
        appendRow(
            wb.sheetFor(ExportTable.INCOME_SAVINGS),
            nextYear.id, LocalDate.of(2026, 5, 1), SavingsCategory.Other.label, "Wrong year", 10.0,
        )

        val result = import(wb)

        assertTrue(result is ImportResult.Failure)
        assertTrue((result as ImportResult.Failure).errors.single().contains("does not exist for tax year 2026-27"))
    }

    // ── final declaration ─────────────────────────────────────────────────

    @Test
    fun `a finally declared tax year is refused and nothing is written`() {
        val property = ukProperty()
        val wb = exportWorkbook(ExportTable.INCOME_PROPERTY_UK)
        appendRow(
            wb.sheetFor(ExportTable.INCOME_PROPERTY_UK),
            null, null, display(property), LocalDate.of(2026, 6, 1),
            IncomeCategory.TotalRentReceived.label, "Rent", 100.0,
        )
        declareFinal()

        val result = import(wb)

        assertTrue(result is ImportResult.Failure)
        assertTrue((result as ImportResult.Failure).errors.single().contains("finally declared"))
        assertTrue(IncomePropertyUkRepository.currentPropertyIncomeForYear(userId, taxYear).isEmpty())
    }

    @Test
    fun `a year declared after the conflict check is refused when the user proceeds`() {
        seedSavings()
        val wb = exportWorkbook(ExportTable.INCOME_SAVINGS)
        appendRow(
            wb.sheetFor(ExportTable.INCOME_SAVINGS),
            null, LocalDate.of(2026, 5, 1), SavingsCategory.BankBuildingSociety.label, "Interest", 50.0,
        )

        val first = import(wb)
        assertTrue(first is ImportResult.ConflictsFound)
        declareFinal()
        val proceeded = (first as ImportResult.ConflictsFound).proceed()

        assertTrue(proceeded is ImportResult.Failure)
        assertTrue((proceeded as ImportResult.Failure).errors.single().contains("Nothing was imported"))
        assertEquals(1, IncomeSavingsRepository.currentForTaxYear(userId, taxYear).size)
    }

    // ── atomicity ─────────────────────────────────────────────────────────

    /** Delegates to a real spec but fails on insert, to simulate a failure part-way through an import. */
    private class FailingInsertSpec(private val delegate: SheetSpec) : SheetSpec by delegate {
        override fun insert(userId: Int, taxYear: String, values: Map<String, Any?>) {
            throw IllegalStateException("simulated failure")
        }
    }

    @Test
    fun `a failure on a later sheet rolls back rows already written for an earlier one`() {
        val property = ukProperty()
        val wb = exportWorkbook(ExportTable.INCOME_PROPERTY_UK, ExportTable.INCOME_SAVINGS)
        appendRow(
            wb.sheetFor(ExportTable.INCOME_PROPERTY_UK),
            null, null, display(property), LocalDate.of(2026, 6, 1),
            IncomeCategory.TotalRentReceived.label, "Rent", 100.0,
        )
        appendRow(
            wb.sheetFor(ExportTable.INCOME_SAVINGS),
            null, LocalDate.of(2026, 6, 1), SavingsCategory.Other.label, "Interest", 5.0,
        )

        val result = import(wb, listOf(IncomePropertyUkSheetSpec, FailingInsertSpec(IncomeSavingsSheetSpec)))

        assertTrue(result is ImportResult.Failure)
        assertTrue((result as ImportResult.Failure).errors.single().contains("rolled back"))
        assertTrue(IncomePropertyUkRepository.currentPropertyIncomeForYear(userId, taxYear).isEmpty())
        assertTrue(IncomeSavingsRepository.currentForTaxYear(userId, taxYear).isEmpty())
    }

    @Test
    fun `an error on any sheet stops the whole import before anything is written`() {
        val property = ukProperty()
        val wb = exportWorkbook(ExportTable.INCOME_PROPERTY_UK, ExportTable.INCOME_SAVINGS)
        appendRow(
            wb.sheetFor(ExportTable.INCOME_PROPERTY_UK),
            null, null, display(property), LocalDate.of(2026, 6, 1),
            IncomeCategory.TotalRentReceived.label, "Rent", 100.0,
        )
        appendRow(
            wb.sheetFor(ExportTable.INCOME_SAVINGS),
            null, LocalDate.of(2026, 6, 1), SavingsCategory.Other.label, "Bad amount", -5.0,
        )

        val result = import(wb)

        assertTrue(result is ImportResult.Failure)
        assertTrue(IncomePropertyUkRepository.currentPropertyIncomeForYear(userId, taxYear).isEmpty())
    }

    // ── conflicts ─────────────────────────────────────────────────────────

    @Test
    fun `a new row duplicating an existing one is reported as a conflict, and proceeding inserts it`() {
        val existing = seedSavings()
        val wb = exportWorkbook(ExportTable.INCOME_SAVINGS)
        appendRow(
            wb.sheetFor(ExportTable.INCOME_SAVINGS),
            null, LocalDate.of(2026, 5, 1), SavingsCategory.BankBuildingSociety.label, "Interest", 50.0,
        )

        val result = import(wb)

        assertTrue(result is ImportResult.ConflictsFound)
        val conflict = (result as ImportResult.ConflictsFound).conflicts.single()
        assertEquals(existing.id, conflict.existingDbId)
        assertEquals(ExportTable.INCOME_SAVINGS.label, conflict.sheetName)
        assertEquals(4, conflict.sheetRowNum)
        assertTrue(conflict.summary.contains("Interest"))

        val proceeded = summaryOf(result.proceed())
        assertEquals(1, proceeded.inserted)
        assertEquals(1, proceeded.unchanged)
        assertEquals(2, IncomeSavingsRepository.currentForTaxYear(userId, taxYear).size)
    }

    // ── which sheets are imported ─────────────────────────────────────────

    @Test
    fun `a workbook with no recognised sheet is rejected`() {
        val wb = XSSFWorkbook().also { it.createSheet("Nothing useful") }

        val result = import(wb)

        assertTrue(result is ImportResult.Failure)
        assertTrue((result as ImportResult.Failure).errors.single().contains("No recognised sheet"))
    }

    @Test
    fun `unrecognised sheets are ignored and a subset of sheets is imported`() {
        val wb = exportWorkbook(ExportTable.INCOME_SAVINGS)
        wb.createSheet("Notes")
        wb.createSheet("_dv_9")
        appendRow(
            wb.sheetFor(ExportTable.INCOME_SAVINGS),
            null, LocalDate.of(2026, 6, 1), SavingsCategory.Other.label, "Interest", 5.0,
        )

        val summary = summaryOf(import(wb))

        assertEquals(1, summary.inserted)
    }

    @Test
    fun `a changed db name header is reported and nothing is imported`() {
        val wb = exportWorkbook(ExportTable.INCOME_SAVINGS)
        wb.sheetFor(ExportTable.INCOME_SAVINGS).getRow(1).getCell(2).setCellValue("wrong")
        appendRow(
            wb.sheetFor(ExportTable.INCOME_SAVINGS),
            null, LocalDate.of(2026, 6, 1), SavingsCategory.Other.label, "Interest", 5.0,
        )

        val result = import(wb)

        assertTrue(result is ImportResult.Failure)
        assertTrue(IncomeSavingsRepository.currentForTaxYear(userId, taxYear).isEmpty())
    }

    // ── round trip through a real file ────────────────────────────────────

    @Test
    fun `exporting every table to a file and importing it unchanged changes nothing`(@TempDir tempDir: Path) {
        // The comma in the first address exercises the hidden-helper-sheet dropdown end to end.
        val uk      = ukProperty("42 Imaginary Crescent, Madeup-on-Sea", "MS2 7UP")
        val foreign = foreignProperty()
        seedIncome(uk)
        ExpensePropertyUkRepository.record(
            periodId("2026-05-02"), userId, uk.id, ExpenseCategory.TravelCosts.dbKey, 30.0, "Fuel", "2026-05-02",
        )
        IncomePropertyForeignRepository.recordForeignPropertyIncome(
            periodId("2026-05-05"), userId, foreign.id, ForeignIncomeCategory.RentIncome.dbKey,
            900.0, "May rent", "2026-05-05",
        )
        ExpensePropertyForeignRepository.recordForeignPropertyExpense(
            periodId("2026-05-06"), userId, foreign.id, ForeignExpenseCategory.Other.dbKey,
            45.0, "Agent", "2026-05-06",
        )
        IncomeDividendRepository.recordDividend(
            userId, taxYear, DividendCategory.UkDividends.dbKey, 60.0, null, "Company", "2026-05-03",
        )
        IncomeDividendRepository.recordDividend(
            userId, taxYear, DividendScalarCategory.StockDividend.dbKey, 70.0, "REF1", "REF1", "2026-05-07",
        )
        IncomeDividendForeignRepository.record(
            userId, taxYear, DividendForeignCategory.ForeignDividend.dbKey, "FRA",
            100.0, 10.0, null, true, 90.0, "2026-05-08",
        )
        seedSavings(date = "2026-05-04")
        val file = tempDir.resolve("roundtrip.xlsx")

        SpreadsheetExporter.exportTables(ExportTable.entries, userId, taxYear, null, file)
        val summary = summaryOf(SpreadsheetImporter.import(file, userId, taxYear))

        assertEquals(0, summary.inserted)
        assertEquals(0, summary.superseded)
        assertEquals(0, summary.deleted)
        assertEquals(8, summary.unchanged)
    }

    @Test
    fun `a file that is not a spreadsheet is rejected`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("not-a-spreadsheet.xlsx")
        Files.writeString(file, "this is not a workbook")

        val result = SpreadsheetImporter.import(file, userId, taxYear)

        assertTrue(result is ImportResult.Failure)
        assertTrue((result as ImportResult.Failure).errors.single().contains("Cannot open file"))
    }
}
