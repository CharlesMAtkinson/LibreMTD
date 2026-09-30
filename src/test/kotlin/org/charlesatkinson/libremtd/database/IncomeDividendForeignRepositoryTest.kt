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

import org.charlesatkinson.libremtd.database.components.FinalDeclarationLockedException
import org.charlesatkinson.libremtd.database.tables.IncomeDividendForeignEntries
import org.charlesatkinson.libremtd.database.tables.Periods
import org.charlesatkinson.libremtd.database.tables.Submissions
import org.charlesatkinson.libremtd.database.tables.Users
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.io.path.deleteIfExists

class IncomeDividendForeignRepositoryTest {

    private lateinit var db: Database
    private lateinit var dbFile: Path

    @BeforeEach
    fun setUp() {
        dbFile = Files.createTempFile("libremtd-test-", ".sqlite")
        db = Database.connect("jdbc:sqlite:${dbFile}", driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(Users, Periods, Submissions, IncomeDividendForeignEntries)
        }
    }

    @AfterEach
    fun tearDown() {
        transaction(db) {
            SchemaUtils.drop(IncomeDividendForeignEntries, Submissions, Periods, Users)
        }
        dbFile.deleteIfExists()
    }

    private fun insertUser(id: Int) {
        transaction(db) {
            Users.insert {
                it[Users.id]           = id
                it[Users.username]     = "user$id"
                it[Users.passwordHash] = "hash"
                it[Users.createdAt]    = LocalDateTime.now()
            }
        }
    }

    @Test
    fun `edit supersedes the old entry and inserts a new one with the updated values`() {
        insertUser(1)
        val original = IncomeDividendForeignRepository.record(
            userId = 1, taxYear = "2026-27", category = "ForeignDividend",
            countryCode = "FRA", amountBeforeTax = 100.0, taxTakenOff = 10.0,
            specialWithholdingTax = null, foreignTaxCreditRelief = false,
            taxableAmount = 90.0, transactionDate = "2026-05-01",
        )

        val edited = IncomeDividendForeignRepository.edit(
            existingId = original.id, userId = 1, taxYear = "2026-27", category = "ForeignDividend",
            countryCode = "DEU", amountBeforeTax = 200.0, taxTakenOff = 20.0,
            specialWithholdingTax = 5.0, foreignTaxCreditRelief = true,
            taxableAmount = 175.0, transactionDate = "2026-05-02",
        )

        assertEquals("DEU", edited.countryCode)
        assertEquals(200.0, edited.amountBeforeTax)
        assertEquals(true, edited.foreignTaxCreditRelief)
        assertEquals(175.0, edited.taxableAmount)

        val current = IncomeDividendForeignRepository.currentEntriesForYear(1, "2026-27")
        assertEquals(1, current.size)
        assertEquals(edited.id, current.single().id)
    }

    @Test
    fun `edit throws FinalDeclarationLockedException when the tax year is finally declared`() {
        insertUser(1)
        val original = IncomeDividendForeignRepository.record(
            userId = 1, taxYear = "2026-27", category = "ForeignDividend",
            countryCode = "FRA", amountBeforeTax = 100.0, taxTakenOff = 10.0,
            specialWithholdingTax = null, foreignTaxCreditRelief = false,
            taxableAmount = 90.0, transactionDate = "2026-05-01",
        )
        SubmissionRepository.record(
            userId = 1, periodId = null, taxYear = "2026-27",
            submissionType = "final_declaration", hmrcResponse = "204",
        )

        assertThrows(FinalDeclarationLockedException::class.java) {
            IncomeDividendForeignRepository.edit(
                existingId = original.id, userId = 1, taxYear = "2026-27", category = "ForeignDividend",
                countryCode = "DEU", amountBeforeTax = 200.0, taxTakenOff = 20.0,
                specialWithholdingTax = 5.0, foreignTaxCreditRelief = true,
                taxableAmount = 175.0, transactionDate = "2026-05-02",
            )
        }
    }

    @Test
    fun `edit does not affect other entries`() {
        insertUser(1)
        val target = IncomeDividendForeignRepository.record(
            userId = 1, taxYear = "2026-27", category = "ForeignDividend",
            countryCode = "FRA", amountBeforeTax = 100.0, taxTakenOff = 10.0,
            specialWithholdingTax = null, foreignTaxCreditRelief = false,
            taxableAmount = 90.0, transactionDate = "2026-05-01",
        )
        val other = IncomeDividendForeignRepository.record(
            userId = 1, taxYear = "2026-27", category = "DividendWhilstAbroad",
            countryCode = "USA", amountBeforeTax = 50.0, taxTakenOff = null,
            specialWithholdingTax = null, foreignTaxCreditRelief = false,
            taxableAmount = 50.0, transactionDate = "2026-05-03",
        )

        IncomeDividendForeignRepository.edit(
            existingId = target.id, userId = 1, taxYear = "2026-27", category = "ForeignDividend",
            countryCode = "DEU", amountBeforeTax = 200.0, taxTakenOff = 20.0,
            specialWithholdingTax = 5.0, foreignTaxCreditRelief = true,
            taxableAmount = 175.0, transactionDate = "2026-05-02",
        )

        val current = IncomeDividendForeignRepository.currentEntriesForYear(1, "2026-27")
        assertEquals(2, current.size)
        val untouched = current.single { it.id == other.id }
        assertEquals("USA", untouched.countryCode)
        assertEquals(50.0, untouched.taxableAmount)
    }
}
