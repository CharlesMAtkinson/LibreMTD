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

package org.charlesatkinson.libremtd.database.components

import org.charlesatkinson.libremtd.database.SubmissionRepository
import org.charlesatkinson.libremtd.database.tables.Periods
import org.charlesatkinson.libremtd.database.tables.Submissions
import org.charlesatkinson.libremtd.database.tables.Users
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.io.path.deleteIfExists

class FinalDeclarationGuardTest {

    private lateinit var db: Database
    private lateinit var dbFile: Path

    @BeforeEach
    fun setUp() {
        // Real temp-file SQLite, not in-memory. See SettingsRepositoryTest
        // for why: Exposed opens a fresh JDBC connection per transaction{}
        // block, and an in-memory SQLite database is wiped as soon as its
        // one open connection closes.
        dbFile = Files.createTempFile("libremtd-test-", ".sqlite")
        db = Database.connect("jdbc:sqlite:${dbFile}", driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(Users, Periods, Submissions)
        }
    }

    @AfterEach
    fun tearDown() {
        transaction(db) {
            SchemaUtils.drop(Submissions, Periods, Users)
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

    private fun declare(userId: Int, taxYear: String) {
        SubmissionRepository.record(
            userId = userId, periodId = null, taxYear = taxYear,
            submissionType = "final_declaration", hmrcResponse = "204",
        )
    }

    @Test
    fun `requireNotFinalDeclared does not throw for an open tax year`() {
        insertUser(1)
        assertDoesNotThrow {
            FinalDeclarationGuard.requireNotFinalDeclared(userId = 1, taxYear = "2026-27")
        }
    }

    @Test
    fun `requireNotFinalDeclared throws for a finally declared tax year`() {
        insertUser(1)
        declare(1, "2026-27")
        val ex = assertThrows(FinalDeclarationLockedException::class.java) {
            FinalDeclarationGuard.requireNotFinalDeclared(userId = 1, taxYear = "2026-27")
        }
        assertEquals("2026-27", ex.taxYear)
    }

    @Test
    fun `requireNotFinalDeclaredForDate throws when the date's tax year is finally declared`() {
        insertUser(1)
        declare(1, "2026-27")
        val ex = assertThrows(FinalDeclarationLockedException::class.java) {
            FinalDeclarationGuard.requireNotFinalDeclaredForDate(userId = 1, transactionDate = "2026-05-01")
        }
        assertEquals("2026-27", ex.taxYear)
    }

    @Test
    fun `requireNotFinalDeclaredForDate does not throw when only a different tax year is declared`() {
        insertUser(1)
        declare(1, "2026-27")
        assertDoesNotThrow {
            FinalDeclarationGuard.requireNotFinalDeclaredForDate(userId = 1, transactionDate = "2027-04-06")
        }
    }

    @Test
    fun `requireNotFinalDeclaredForDate treats 5 April as the last day of the earlier tax year`() {
        insertUser(1)
        declare(1, "2026-27")
        assertThrows(FinalDeclarationLockedException::class.java) {
            FinalDeclarationGuard.requireNotFinalDeclaredForDate(userId = 1, transactionDate = "2027-04-05")
        }
    }
}
