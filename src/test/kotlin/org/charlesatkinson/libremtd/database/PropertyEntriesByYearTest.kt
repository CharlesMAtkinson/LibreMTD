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

package org.charlesatkinson.libremtd.database

import org.charlesatkinson.libremtd.database.tables.ExpensePropertyForeignEntries
import org.charlesatkinson.libremtd.database.tables.ExpensePropertyUkEntries
import org.charlesatkinson.libremtd.database.tables.IncomePropertyForeignEntries
import org.charlesatkinson.libremtd.database.tables.IncomePropertyUkEntries
import org.charlesatkinson.libremtd.database.tables.Periods
import org.charlesatkinson.libremtd.database.tables.Properties
import org.charlesatkinson.libremtd.database.tables.Submissions
import org.charlesatkinson.libremtd.database.tables.Users
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

/**
 * Tests for the currentForPropertyAndYear queries used by the four
 * property income/expense panes: each must return only the given
 * property's current entries whose transaction date is inside the given
 * tax year.
 */
class PropertyEntriesByYearTest {

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
            )
        }
    }

    @AfterEach
    fun tearDown() {
        transaction(db) {
            SchemaUtils.drop(
                ExpensePropertyForeignEntries, IncomePropertyForeignEntries,
                ExpensePropertyUkEntries, IncomePropertyUkEntries,
                Properties, Submissions, Periods, Users,
            )
        }
        dbFile.deleteIfExists()
    }

    private fun periodId(date: String) = PeriodRepository.getOrCreateStandard(date).id

    private fun ukProperty(address: String) =
        PropertyRepository.create(userId = 1, address = address, propertyType = PropertyType.UK, postcode = "AB1 2CD")

    private fun foreignProperty(address: String) =
        PropertyRepository.create(userId = 1, address = address, propertyType = PropertyType.FOREIGN, countryCode = "FRA")

    @Test
    fun `UK income returns only the given property's entries within the given tax year`() {
        val p1 = ukProperty("1 High Street")
        val p2 = ukProperty("2 Low Street")
        fun add(p: Property, date: String, desc: String) =
            IncomePropertyUkRepository.recordPropertyIncome(
                periodId = periodId(date), userId = 1, propertyId = p.id,
                category = "TotalRentReceived", amount = 100.0, description = desc, transactionDate = date,
            )
        add(p1, "2026-05-01", "p1 in year")
        add(p1, "2027-05-01", "p1 next year")
        add(p2, "2026-05-02", "p2 in year")

        val found = IncomePropertyUkRepository.currentForPropertyAndYear(p1.id, "2026-27")

        assertEquals(listOf("p1 in year"), found.map { it.description })
    }

    @Test
    fun `UK income year boundaries are 6 April to 5 April inclusive`() {
        val p = ukProperty("1 High Street")
        fun add(date: String) =
            IncomePropertyUkRepository.recordPropertyIncome(
                periodId = periodId(date), userId = 1, propertyId = p.id,
                category = "TotalRentReceived", amount = 100.0, description = date, transactionDate = date,
            )
        add("2026-04-05")
        add("2026-04-06")
        add("2027-04-05")
        add("2027-04-06")

        val found = IncomePropertyUkRepository.currentForPropertyAndYear(p.id, "2026-27")

        assertEquals(listOf("2026-04-06", "2027-04-05"), found.map { it.transactionDate })
    }

    @Test
    fun `UK income excludes superseded entries after an edit`() {
        val p = ukProperty("1 High Street")
        val original = IncomePropertyUkRepository.recordPropertyIncome(
            periodId = periodId("2026-05-01"), userId = 1, propertyId = p.id,
            category = "TotalRentReceived", amount = 100.0, description = "Original", transactionDate = "2026-05-01",
        )
        IncomePropertyUkRepository.edit(
            existingId = original.id, periodId = periodId("2026-05-01"), userId = 1, propertyId = p.id,
            category = "TotalRentReceived", amount = 150.0, description = "Corrected", transactionDate = "2026-05-01",
        )

        val found = IncomePropertyUkRepository.currentForPropertyAndYear(p.id, "2026-27")

        assertEquals(listOf("Corrected"), found.map { it.description })
    }

    @Test
    fun `UK expenses returns only the given property's entries within the given tax year`() {
        val p1 = ukProperty("1 High Street")
        val p2 = ukProperty("2 Low Street")
        fun add(p: Property, date: String, desc: String) =
            ExpensePropertyUkRepository.record(
                periodId = periodId(date), userId = 1, propertyId = p.id,
                category = "RepairsAndMaintenance", amount = 50.0, description = desc, transactionDate = date,
            )
        add(p1, "2026-06-01", "p1 in year")
        add(p1, "2027-06-01", "p1 next year")
        add(p2, "2026-06-02", "p2 in year")

        val found = ExpensePropertyUkRepository.currentForPropertyAndYear(p1.id, "2026-27")

        assertEquals(listOf("p1 in year"), found.map { it.description })
    }

    @Test
    fun `foreign income returns only the given property's entries within the given tax year`() {
        val p1 = foreignProperty("10 Rue de Paris")
        val p2 = foreignProperty("20 Rue de Lyon")
        fun add(p: Property, date: String, desc: String) =
            IncomePropertyForeignRepository.recordForeignPropertyIncome(
                periodId = periodId(date), userId = 1, propertyId = p.id,
                category = "RentIncome", amount = 500.0, description = desc, transactionDate = date,
            )
        add(p1, "2026-08-01", "p1 in year")
        add(p1, "2027-08-01", "p1 next year")
        add(p2, "2026-08-02", "p2 in year")

        val found = IncomePropertyForeignRepository.currentForPropertyAndYear(p1.id, "2026-27")

        assertEquals(listOf("p1 in year"), found.map { it.description })
    }

    @Test
    fun `foreign expenses returns only the given property's entries within the given tax year`() {
        val p1 = foreignProperty("10 Rue de Paris")
        val p2 = foreignProperty("20 Rue de Lyon")
        fun add(p: Property, date: String, desc: String) =
            ExpensePropertyForeignRepository.recordForeignPropertyExpense(
                periodId = periodId(date), userId = 1, propertyId = p.id,
                category = "Other", amount = 25.0, description = desc, transactionDate = date,
            )
        add(p1, "2026-09-01", "p1 in year")
        add(p1, "2027-09-01", "p1 next year")
        add(p2, "2026-09-02", "p2 in year")

        val found = ExpensePropertyForeignRepository.currentForPropertyAndYear(p1.id, "2026-27")

        assertEquals(listOf("p1 in year"), found.map { it.description })
    }
}
