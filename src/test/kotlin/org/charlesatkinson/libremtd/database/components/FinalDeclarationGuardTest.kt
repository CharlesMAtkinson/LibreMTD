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

import org.charlesatkinson.libremtd.database.PeriodRepository
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
        // Real temp-file SQLite, not in-memory — see SettingsRepositoryTest
        // for why: Exposed opens a fresh JDBC connection per transaction{}
        // block, and an in-memory SQLite database is wiped as soon as its
        // one open connection closes, so schema created in one transaction
        // would vanish before the next.
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
        SubmissionRepository.record(
            userId = 1, periodId = null, taxYear = "2026-27",
            submissionType = "final_declaration", hmrcResponse = "204",
        )
        val ex = assertThrows(FinalDeclarationLockedException::class.java) {
            FinalDeclarationGuard.requireNotFinalDeclared(userId = 1, taxYear = "2026-27")
        }
        assertEquals("2026-27", ex.taxYear)
    }

    @Test
    fun `requireNotFinalDeclaredForPeriod resolves the tax year and throws when locked`() {
        insertUser(1)
        val period = PeriodRepository.upsert(
            taxYear = "2026-27", periodKey = "#001",
            startDate = "2026-04-06", endDate = "2026-07-05", dueDate = "2026-08-05",
        )
        SubmissionRepository.record(
            userId = 1, periodId = null, taxYear = "2026-27",
            submissionType = "final_declaration", hmrcResponse = "204",
        )
        assertThrows(FinalDeclarationLockedException::class.java) {
            FinalDeclarationGuard.requireNotFinalDeclaredForPeriod(userId = 1, periodId = period.id)
        }
    }

    @Test
    fun `requireNotFinalDeclaredForPeriod does not throw when the period's tax year is open`() {
        insertUser(1)
        val period = PeriodRepository.upsert(
            taxYear = "2025-26", periodKey = "#001",
            startDate = "2025-04-06", endDate = "2025-07-05", dueDate = "2025-08-05",
        )
        SubmissionRepository.record(
            userId = 1, periodId = null, taxYear = "2026-27",
            submissionType = "final_declaration", hmrcResponse = "204",
        )
        assertDoesNotThrow {
            FinalDeclarationGuard.requireNotFinalDeclaredForPeriod(userId = 1, periodId = period.id)
        }
    }

    @Test
    fun `requireNotFinalDeclaredForPeriod does nothing for an unknown period id`() {
        insertUser(1)
        assertDoesNotThrow {
            FinalDeclarationGuard.requireNotFinalDeclaredForPeriod(userId = 1, periodId = 999)
        }
    }
}
