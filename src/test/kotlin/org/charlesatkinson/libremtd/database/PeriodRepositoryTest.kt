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

import org.charlesatkinson.libremtd.database.tables.Periods
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

class PeriodRepositoryTest {

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
            SchemaUtils.create(Periods)
        }
    }

    @AfterEach
    fun tearDown() {
        transaction(db) {
            SchemaUtils.drop(Periods)
        }
        dbFile.deleteIfExists()
    }

    @Test
    fun `upsert creates a new period and findById returns it`() {
        val created = PeriodRepository.upsert(
            taxYear   = "2026-27",
            periodKey = "#001",
            startDate = "2026-04-06",
            endDate   = "2026-07-05",
            dueDate   = "2026-08-05",
        )

        val found = PeriodRepository.findById(created.id)
        assertEquals(created, found)
    }

    @Test
    fun `upsert called again for the same taxYear and periodKey does not create a duplicate row`() {
        PeriodRepository.upsert("2026-27", "#001", "2026-04-06", "2026-07-05", "2026-08-05")
        PeriodRepository.upsert("2026-27", "#001", "2026-04-06", "2026-07-05", "2026-08-05")

        val matching = PeriodRepository.findByTaxYear("2026-27").filter { it.periodKey == "#001" }
        assertEquals(1, matching.size)
    }

    @Test
    fun `upsert persists new dates when the period already exists`() {
        val first = PeriodRepository.upsert("2026-27", "#001", "2026-04-06", "2026-07-05", "2026-08-05")

        val second = PeriodRepository.upsert("2026-27", "#001", "2026-04-07", "2026-07-06", "2026-08-06")

        // Same row (same id), and both the returned object and the
        // persisted row now reflect the corrected dates.
        assertEquals(first.id, second.id)
        assertEquals("2026-04-07", second.startDate)

        val persisted = PeriodRepository.findById(first.id)
        assertEquals("2026-04-07", persisted?.startDate)
        assertEquals("2026-07-06", persisted?.endDate)
        assertEquals("2026-08-06", persisted?.dueDate)
    }

    @Test
    fun `findAll returns periods ordered by taxYear then periodKey`() {
        PeriodRepository.upsert("2026-27", "#002", "2026-07-06", "2026-10-05", "2026-11-05")
        PeriodRepository.upsert("2025-26", "#001", "2025-04-06", "2025-07-05", "2025-08-05")
        PeriodRepository.upsert("2026-27", "#001", "2026-04-06", "2026-07-05", "2026-08-05")

        val all = PeriodRepository.findAll()

        assertEquals(
            listOf("2025-26" to "#001", "2026-27" to "#001", "2026-27" to "#002"),
            all.map { it.taxYear to it.periodKey },
        )
    }

    @Test
    fun `findById returns null for an unknown id`() {
        assertNull(PeriodRepository.findById(999))
    }

    @Test
    fun `findByTaxYear returns only periods for that tax year`() {
        PeriodRepository.upsert("2025-26", "#001", "2025-04-06", "2025-07-05", "2025-08-05")
        PeriodRepository.upsert("2026-27", "#001", "2026-04-06", "2026-07-05", "2026-08-05")
        PeriodRepository.upsert("2026-27", "#002", "2026-07-06", "2026-10-05", "2026-11-05")

        val found = PeriodRepository.findByTaxYear("2026-27")

        assertTrue(found.all { it.taxYear == "2026-27" })
        assertEquals(2, found.size)
    }
}
