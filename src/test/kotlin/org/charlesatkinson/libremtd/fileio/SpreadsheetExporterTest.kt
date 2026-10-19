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

import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.charlesatkinson.libremtd.database.ExpensePropertyForeignRepository
import org.charlesatkinson.libremtd.database.ExpensePropertyUkRepository
import org.charlesatkinson.libremtd.database.IncomeDividendRepository
import org.charlesatkinson.libremtd.database.IncomePropertyForeignRepository
import org.charlesatkinson.libremtd.database.IncomePropertyUkRepository
import org.charlesatkinson.libremtd.database.IncomeSavingsRepository
import org.charlesatkinson.libremtd.database.PeriodRepository
import org.charlesatkinson.libremtd.database.Property
import org.charlesatkinson.libremtd.database.PropertyRepository
import org.charlesatkinson.libremtd.database.PropertyType
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
import org.charlesatkinson.libremtd.fileio.components.ColumnType
import org.charlesatkinson.libremtd.fileio.components.ExportTable
import org.charlesatkinson.libremtd.fileio.components.IncomeDividendSheetSpec
import org.charlesatkinson.libremtd.fileio.components.sheetSpecFor
import org.charlesatkinson.libremtd.ui.DividendCategory
import org.charlesatkinson.libremtd.ui.DividendScalarCategory
import org.charlesatkinson.libremtd.ui.ForeignIncomeCategory
import org.charlesatkinson.libremtd.ui.IncomeCategory
import org.charlesatkinson.libremtd.ui.SavingsCategory
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.deleteIfExists
import kotlin.io.path.inputStream

class SpreadsheetExporterTest {

    private val userId  = 1
    private val taxYear = "2026-27"

    private lateinit var db: Database
    private lateinit var dbFile: Path

    @BeforeEach
    fun setUp() {
        // Real temp-file SQLite, not in-memory: see SettingsRepositoryTest.
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
            userId = userId, address = address, propertyType = PropertyType.FOREIGN,
            countryCode = "FRA",
        )

    private fun periodId(date: String) = PeriodRepository.getOrCreateStandard(date).id

    private fun addIncome(
        property: Property, date: String = "2026-05-01", amount: Double = 100.0, description: String = "Rent",
    ) = IncomePropertyUkRepository.recordPropertyIncome(
        periodId(date), userId, property.id, IncomeCategory.TotalRentReceived.dbKey, amount, description, date,
    )

    private fun workbookFor(vararg tables: ExportTable): XSSFWorkbook =
        SpreadsheetExporter.buildWorkbook(tables.associateWith { sheetDataFor(it, userId, taxYear) })

    // ── property display string ───────────────────────────────────────────

    @Test
    fun `a UK property is shown as address then postcode, keeping the commas in the address`() {
        assertEquals(
            "42 Imaginary Crescent, Madeup-on-Sea MS2 7UP",
            propertyDisplayString("42 Imaginary Crescent, Madeup-on-Sea", "MS2 7UP"),
        )
    }

    @Test
    fun `a foreign property is shown with its country code in parentheses`() {
        assertEquals("10 Rue de Paris (FRA)", propertyDisplayString("10 Rue de Paris", null, "FRA"))
    }

    @Test
    fun `a property with neither postcode nor country code is shown as its address`() {
        assertEquals("Somewhere", propertyDisplayString("Somewhere", null, null))
    }

    // ── layout ────────────────────────────────────────────────────────────

    @Test
    fun `tables are ordered as in the side pane`() {
        assertEquals(
            listOf(
                "Income (dividends)",
                "Income (dividends, special)",
                "Income (dividends, foreign)",
                "Income (property, UK)",
                "Income (property, foreign)",
                "Income (savings)",
                "Expenses (property, UK)",
                "Expenses (property, foreign)",
            ),
            ExportTable.entries.map { it.label },
        )
    }

    @Test
    fun `each requested table gets a sheet named after its label, within Excel's 31 character limit`() {
        val wb = workbookFor(*ExportTable.entries.toTypedArray())

        assertEquals(ExportTable.entries.map { it.label }, (0 until wb.numberOfSheets).map { wb.getSheetName(it) })
        assertTrue(ExportTable.entries.all { it.label.length <= 31 })
    }

    @Test
    fun `UK property sheets follow the GUI column names and order`() {
        ukProperty()
        for (table in listOf(ExportTable.INCOME_PROPERTY_UK, ExportTable.EXPENSES_PROPERTY_UK)) {
            val sheet = workbookFor(table).getSheet(table.label)
            val headers = (0..6).map { sheet.getRow(0).getCell(it).stringCellValue }

            assertEquals(listOf("ID", "", "Property", "Date", "Category", "Description", "Amount (£)"), headers)
        }
    }

    @Test
    fun `the dividends sheet uses Type as in the GUI table`() {
        val sheet = workbookFor(ExportTable.INCOME_DIVIDENDS).getSheet(ExportTable.INCOME_DIVIDENDS.label)

        assertEquals(
            listOf("ID", "Date", "Type", "Description", "Amount (£)"),
            (0..4).map { sheet.getRow(0).getCell(it).stringCellValue },
        )
    }

    @Test
    fun `the savings sheet follows the GUI column names and order`() {
        val sheet = workbookFor(ExportTable.INCOME_SAVINGS).getSheet(ExportTable.INCOME_SAVINGS.label)

        assertEquals(
            listOf("ID", "Date", "Category", "Description", "Amount (£)"),
            (0..4).map { sheet.getRow(0).getCell(it).stringCellValue },
        )
    }

    @Test
    fun `row 1 holds the db names and has zero height, and hidden columns have a blank friendly header`() {
        ukProperty()
        val wb    = workbookFor(ExportTable.INCOME_PROPERTY_UK)
        val sheet = wb.getSheet(ExportTable.INCOME_PROPERTY_UK.label)
        val cols  = sheetSpecFor(ExportTable.INCOME_PROPERTY_UK).columns(emptyList())

        assertEquals(cols.map { it.dbName }, cols.indices.map { sheet.getRow(1).getCell(it).stringCellValue })
        assertTrue(sheet.getRow(1).zeroHeight)
        assertEquals("", sheet.getRow(0).getCell(1).stringCellValue)
    }

    @Test
    fun `property_id is hidden and the id column is not`() {
        val sheet = workbookFor(ExportTable.INCOME_PROPERTY_UK).getSheet(ExportTable.INCOME_PROPERTY_UK.label)

        assertFalse(sheet.isColumnHidden(0))
        assertTrue(sheet.isColumnHidden(1))
    }

    @Test
    fun `all four property sheets have a property column, and the others do not`() {
        val withProperty = listOf(
            ExportTable.INCOME_PROPERTY_UK, ExportTable.INCOME_PROPERTY_FOREIGN,
            ExportTable.EXPENSES_PROPERTY_UK, ExportTable.EXPENSES_PROPERTY_FOREIGN,
        )
        for (table in ExportTable.entries) {
            val cols = sheetSpecFor(table).columns(emptyList())
            if (table in withProperty) {
                assertTrue(cols.any { it.dbName == "property" && it.type == ColumnType.PROPERTY }, table.label)
                assertTrue(cols.any { it.dbName == "property_id" && it.hidden }, table.label)
            } else {
                assertFalse(cols.any { it.dbName.startsWith("property") }, table.label)
            }
        }
    }

    // ── cell content, formats and locking ─────────────────────────────────

    @Test
    fun `dates are written as real dates and categories as labels`() {
        val property = ukProperty()
        addIncome(property, date = "2026-05-01")
        val sheet = workbookFor(ExportTable.INCOME_PROPERTY_UK).getSheet(ExportTable.INCOME_PROPERTY_UK.label)

        val dateCell = sheet.getRow(2).getCell(3)
        assertTrue(DateUtil.isCellDateFormatted(dateCell))
        assertEquals(LocalDate.of(2026, 5, 1), dateCell.localDateTimeCellValue.toLocalDate())
        assertEquals(IncomeCategory.TotalRentReceived.label, sheet.getRow(2).getCell(4).stringCellValue)
        assertEquals("Rent", sheet.getRow(2).getCell(5).stringCellValue)
        assertEquals(100.0, sheet.getRow(2).getCell(6).numericCellValue)
    }

    @Test
    fun `id cells are locked and editable cells are unlocked`() {
        addIncome(ukProperty())
        val row = workbookFor(ExportTable.INCOME_PROPERTY_UK)
            .getSheet(ExportTable.INCOME_PROPERTY_UK.label).getRow(2)

        assertTrue(row.getCell(0).cellStyle.locked)
        assertTrue(row.getCell(1).cellStyle.locked)
        for (ci in 2..6) assertFalse(row.getCell(ci).cellStyle.locked, "column $ci should be unlocked")
    }

    @Test
    fun `empty editable columns have an unlocked default style so added rows are editable`() {
        val sheet = workbookFor(ExportTable.INCOME_PROPERTY_UK).getSheet(ExportTable.INCOME_PROPERTY_UK.label)

        for (ci in 2..6) {
            val style = sheet.getColumnStyle(ci)
            assertNotNull(style, "column $ci should have a default style")
            assertFalse(style.locked)
        }
    }

    // ── dropdowns ─────────────────────────────────────────────────────────

    @Test
    fun `the UK sheets offer only UK properties and the foreign sheets only foreign ones`() {
        val uk      = ukProperty()
        val foreign = foreignProperty()
        val ukText      = propertyDisplayString(uk.address, uk.postcode, uk.countryCode)
        val foreignText = propertyDisplayString(foreign.address, foreign.postcode, foreign.countryCode)

        fun offered(table: ExportTable) =
            sheetDataFor(table, userId, taxYear).columns.single { it.dbName == "property" }.dropdownValues

        assertEquals(listOf(ukText), offered(ExportTable.INCOME_PROPERTY_UK))
        assertEquals(listOf(ukText), offered(ExportTable.EXPENSES_PROPERTY_UK))
        assertEquals(listOf(foreignText), offered(ExportTable.INCOME_PROPERTY_FOREIGN))
        assertEquals(listOf(foreignText), offered(ExportTable.EXPENSES_PROPERTY_FOREIGN))
    }

    @Test
    fun `dropdown values without commas are written inline as data validations`() {
        val uk = ukProperty()
        val sheet = workbookFor(ExportTable.INCOME_PROPERTY_UK).getSheet(ExportTable.INCOME_PROPERTY_UK.label)

        val lists = sheet.dataValidations.map { it.validationConstraint.explicitListValues.toList() }

        assertTrue(lists.contains(listOf(propertyDisplayString(uk.address, uk.postcode))))
        assertTrue(lists.contains(IncomeCategory.entries.map { it.label }))
    }

    @Test
    fun `a property with a comma in its address goes on a hidden helper sheet, comma intact`() {
        ukProperty("42 Imaginary Crescent, Madeup-on-Sea", "MS2 7UP")
        val wb = workbookFor(ExportTable.INCOME_PROPERTY_UK)

        val helperIndex = (0 until wb.numberOfSheets).single { wb.getSheetName(it).startsWith("_dv_") }

        assertTrue(wb.isSheetHidden(helperIndex))
        assertEquals(
            "42 Imaginary Crescent, Madeup-on-Sea MS2 7UP",
            wb.getSheetAt(helperIndex).getRow(0).getCell(0).stringCellValue,
        )
        val formulas = wb.getSheet(ExportTable.INCOME_PROPERTY_UK.label).dataValidations
            .mapNotNull { it.validationConstraint.formula1 }
        assertTrue(formulas.any { it.contains(wb.getSheetName(helperIndex)) })
    }

    @Test
    fun `a dropdown list over 255 characters goes on a hidden helper sheet`() {
        val longValues = (1..20).map { "Property number $it with a fairly long address AB1 2CD" }
        val columns    = sheetSpecFor(ExportTable.INCOME_PROPERTY_UK).columns(longValues)
        val wb = SpreadsheetExporter.buildWorkbook(
            mapOf(ExportTable.INCOME_PROPERTY_UK to SheetData(columns, emptyList()))
        )

        val helper = (0 until wb.numberOfSheets).single { wb.getSheetName(it).startsWith("_dv_") }
        assertTrue(wb.isSheetHidden(helper))

        val formula = wb.getSheet(ExportTable.INCOME_PROPERTY_UK.label).dataValidations
            .mapNotNull { it.validationConstraint.formula1 }
            .single { it.contains("_dv_") }
        assertTrue(formula.contains(wb.getSheetName(helper)))
    }

    // ── which rows are exported ───────────────────────────────────────────

    @Test
    fun `only current rows are exported, not superseded ones`() {
        val property = ukProperty()
        val original = addIncome(property, amount = 100.0)
        IncomePropertyUkRepository.edit(
            existingId = original.id, periodId = periodId("2026-05-01"), userId = userId,
            propertyId = property.id, category = IncomeCategory.TotalRentReceived.dbKey,
            amount = 250.0, description = "Corrected", transactionDate = "2026-05-01",
        )

        val rows = sheetDataFor(ExportTable.INCOME_PROPERTY_UK, userId, taxYear).rows

        assertEquals(1, rows.size)
        assertEquals(250.0, rows.single()["amount"])
    }

    @Test
    fun `only the selected tax year is exported`() {
        val property = ukProperty()
        addIncome(property, date = "2026-05-01", description = "in year")
        addIncome(property, date = "2027-05-01", description = "next year")

        val rows = sheetDataFor(ExportTable.INCOME_PROPERTY_UK, userId, taxYear).rows

        assertEquals(listOf("in year"), rows.map { it["description"] })
    }

    @Test
    fun `the property filter keeps only that property's rows`() {
        val p1 = ukProperty("1 High Street", "AB1 2CD")
        val p2 = ukProperty("2 Low Street", "AB2 3DE")
        addIncome(p1, description = "p1")
        addIncome(p2, description = "p2")

        val rows = sheetDataFor(ExportTable.INCOME_PROPERTY_UK, userId, taxYear, filterPropertyId = p2.id).rows

        assertEquals(listOf("p2"), rows.map { it["description"] })
    }

    @Test
    fun `the property filter is ignored for sheets without a property column`() {
        val p1 = ukProperty()
        IncomeSavingsRepository.recordSavingsIncome(
            userId, taxYear, SavingsCategory.BankBuildingSociety.dbKey, 50.0, "Interest", "2026-05-01",
        )

        val rows = sheetDataFor(ExportTable.INCOME_SAVINGS, userId, taxYear, filterPropertyId = p1.id).rows

        assertEquals(1, rows.size)
    }

    @Test
    fun `rows are ordered by transaction date`() {
        val property = ukProperty()
        addIncome(property, date = "2026-08-01", description = "later")
        addIncome(property, date = "2026-05-01", description = "earlier")

        val rows = sheetDataFor(ExportTable.INCOME_PROPERTY_UK, userId, taxYear).rows

        assertEquals(listOf("earlier", "later"), rows.map { it["description"] })
    }

    @Test
    fun `expenses rows carry their property`() {
        val property = ukProperty()
        ExpensePropertyUkRepository.record(
            periodId("2026-05-01"), userId, property.id, "RepairsAndMaintenance", 40.0, "Boiler", "2026-05-01",
        )

        val row = sheetDataFor(ExportTable.EXPENSES_PROPERTY_UK, userId, taxYear).rows.single()

        assertEquals(property.id, row["property_id"])
        assertEquals(propertyDisplayString(property.address, property.postcode), row["property"])
    }

    @Test
    fun `foreign income rows carry their property and a category label`() {
        val property = foreignProperty()
        IncomePropertyForeignRepository.recordForeignPropertyIncome(
            periodId("2026-05-01"), userId, property.id, ForeignIncomeCategory.RentIncome.dbKey,
            900.0, "May rent", "2026-05-01",
        )

        val row = sheetDataFor(ExportTable.INCOME_PROPERTY_FOREIGN, userId, taxYear).rows.single()

        assertEquals(property.id, row["property_id"])
        assertEquals("10 Rue de Paris (FRA)", row["property"])
        assertEquals(ForeignIncomeCategory.RentIncome.label, row["category"])
    }

    @Test
    fun `foreign expenses rows are exported for the selected year only`() {
        val property = foreignProperty()
        fun add(date: String, desc: String) = ExpensePropertyForeignRepository.recordForeignPropertyExpense(
            periodId(date), userId, property.id, "Other", 25.0, desc, date,
        )
        add("2026-09-01", "in year")
        add("2027-09-01", "next year")

        val rows = sheetDataFor(ExportTable.EXPENSES_PROPERTY_FOREIGN, userId, taxYear).rows

        assertEquals(listOf("in year"), rows.map { it["description"] })
    }

    @Test
    fun `the dividends sheet exports UK categories only, not special types`() {
        IncomeDividendRepository.recordDividend(
            userId, taxYear, DividendCategory.UkDividends.dbKey, 50.0, null, "Company", "2026-05-01",
        )
        IncomeDividendRepository.recordDividend(
            userId, taxYear, DividendScalarCategory.StockDividend.dbKey, 70.0, "REF1", "", "2026-05-02",
        )

        val rows = sheetDataFor(ExportTable.INCOME_DIVIDENDS, userId, taxYear).rows

        assertEquals(1, rows.size)
        assertEquals(DividendCategory.UkDividends.label, rows.single()["category"])
        assertEquals(1, IncomeDividendSheetSpec.currentRows(userId, taxYear).size)
    }

    // ── writing to disk ───────────────────────────────────────────────────

    @Test
    fun `exportTables writes a readable file with the requested sheets only`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("out.xlsx")

        SpreadsheetExporter.exportTables(
            listOf(ExportTable.INCOME_SAVINGS, ExportTable.INCOME_DIVIDENDS), userId, taxYear, null, file,
        )

        XSSFWorkbook(file.inputStream()).use { wb ->
            assertEquals(2, wb.numberOfSheets)
            assertNotNull(wb.getSheet(ExportTable.INCOME_SAVINGS.label))
            assertNotNull(wb.getSheet(ExportTable.INCOME_DIVIDENDS.label))
        }
    }

    @Test
    fun `suggested file names include the table, qualifier and a timestamp`() {
        val name = SpreadsheetExporter.suggestedFileName(ExportTable.INCOME_SAVINGS, "2026_27")

        assertTrue(name.startsWith("income_savings_2026_27_at_"))
        assertTrue(name.endsWith(".xlsx"))
    }
}