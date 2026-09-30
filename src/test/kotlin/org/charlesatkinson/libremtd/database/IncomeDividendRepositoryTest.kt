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
import org.charlesatkinson.libremtd.database.tables.IncomeDividendEntries
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

class IncomeDividendRepositoryTest {

    private lateinit var db: Database
    private lateinit var dbFile: Path

    @BeforeEach
    fun setUp() {
        dbFile = Files.createTempFile("libremtd-test-", ".sqlite")
        db = Database.connect("jdbc:sqlite:${dbFile}", driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(Users, Periods, Submissions, IncomeDividendEntries)
        }
    }

    @AfterEach
    fun tearDown() {
        transaction(db) {
            SchemaUtils.drop(IncomeDividendEntries, Submissions, Periods, Users)
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
        val original = IncomeDividendRepository.recordDividend(
            userId = 1, taxYear = "2026-27", category = "UkDividends",
            amount = 100.0, description = "Original", transactionDate = "2026-05-01",
        )

        val edited = IncomeDividendRepository.edit(
            existingId = original.id, userId = 1, taxYear = "2026-27", category = "OtherUkDividends",
            amount = 150.0, description = "Corrected", transactionDate = "2026-05-02",
        )

        assertEquals("OtherUkDividends", edited.category)
        assertEquals(150.0, edited.amount)
        assertEquals("Corrected", edited.description)

        val current = IncomeDividendRepository.currentDividendsForYear(1, "2026-27")
        assertEquals(1, current.size)
        assertEquals(edited.id, current.single().id)
    }

    @Test
    fun `edit throws FinalDeclarationLockedException when the tax year is finally declared`() {
        insertUser(1)
        val original = IncomeDividendRepository.recordDividend(
            userId = 1, taxYear = "2026-27", category = "UkDividends",
            amount = 100.0, description = "Original", transactionDate = "2026-05-01",
        )
        SubmissionRepository.record(
            userId = 1, periodId = null, taxYear = "2026-27",
            submissionType = "final_declaration", hmrcResponse = "204",
        )

        assertThrows(FinalDeclarationLockedException::class.java) {
            IncomeDividendRepository.edit(
                existingId = original.id, userId = 1, taxYear = "2026-27", category = "OtherUkDividends",
                amount = 150.0, description = "Corrected", transactionDate = "2026-05-02",
            )
        }
    }

    @Test
    fun `edit does not affect other entries`() {
        insertUser(1)
        val target = IncomeDividendRepository.recordDividend(
            userId = 1, taxYear = "2026-27", category = "UkDividends",
            amount = 100.0, description = "Target", transactionDate = "2026-05-01",
        )
        val other = IncomeDividendRepository.recordDividend(
            userId = 1, taxYear = "2026-27", category = "OtherUkDividends",
            amount = 50.0, description = "Other entry", transactionDate = "2026-05-03",
        )

        IncomeDividendRepository.edit(
            existingId = target.id, userId = 1, taxYear = "2026-27", category = "OtherUkDividends",
            amount = 150.0, description = "Corrected", transactionDate = "2026-05-02",
        )

        val current = IncomeDividendRepository.currentDividendsForYear(1, "2026-27")
        assertEquals(2, current.size)
        val untouched = current.single { it.id == other.id }
        assertEquals("Other entry", untouched.description)
        assertEquals(50.0, untouched.amount)
    }
}
