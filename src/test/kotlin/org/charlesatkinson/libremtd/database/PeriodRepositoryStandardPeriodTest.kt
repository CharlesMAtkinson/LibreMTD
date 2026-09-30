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

package org.charlesatkinson.libremtd.database

import org.charlesatkinson.libremtd.database.tables.Periods
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

/**
 * Covers both standardQuarterFor(date) and standardQuarterBounds(taxYear,
 * periodKey), since the tidy-up moved standardQuarterBounds into this file
 * (from ui.components.TaxQuarterUtils) so the shared quarter-boundary
 * arithmetic lives in one place. The old ui/components/TaxQuarterUtilsTest.kt
 * has been superseded by this file — delete it.
 */
class PeriodRepositoryStandardPeriodTest {

    private lateinit var db: Database
    private lateinit var dbFile: Path

    @BeforeEach
    fun setUp() {
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

    // ── standardQuarterFor: pure calendar arithmetic ───────────────────────

    @Test
    fun `first day of the tax year is in quarter 1`() {
        val q = standardQuarterFor("2026-04-06")
        assertEquals("2026-27", q.taxYear)
        assertEquals("#001", q.periodKey)
        assertEquals("2026-04-06", q.startDate)
        assertEquals("2026-07-05", q.endDate)
        assertEquals("2026-08-07", q.dueDate)
    }

    @Test
    fun `5 July is the last day of quarter 1 and 6 July starts quarter 2`() {
        assertEquals("#001", standardQuarterFor("2026-07-05").periodKey)

        val q2 = standardQuarterFor("2026-07-06")
        assertEquals("#002", q2.periodKey)
        assertEquals("2026-07-06", q2.startDate)
        assertEquals("2026-10-05", q2.endDate)
        assertEquals("2026-11-07", q2.dueDate)
    }

    @Test
    fun `quarter 3 spans the new year and is due in February`() {
        val q = standardQuarterFor("2026-12-25")
        assertEquals("#003", q.periodKey)
        assertEquals("2026-10-06", q.startDate)
        assertEquals("2027-01-05", q.endDate)
        assertEquals("2027-02-07", q.dueDate)
    }

    @Test
    fun `quarter 4 runs from 6 January to 5 April and is due in May`() {
        assertEquals("#003", standardQuarterFor("2027-01-05").periodKey)

        val q = standardQuarterFor("2027-04-05")
        assertEquals("2026-27", q.taxYear)
        assertEquals("#004", q.periodKey)
        assertEquals("2027-01-06", q.startDate)
        assertEquals("2027-04-05", q.endDate)
        assertEquals("2027-05-07", q.dueDate)
    }

    @Test
    fun `6 April starts the next tax year at quarter 1`() {
        val q = standardQuarterFor("2027-04-06")
        assertEquals("2027-28", q.taxYear)
        assertEquals("#001", q.periodKey)
    }

    // ── standardQuarterBounds: same arithmetic, keyed by taxYear+periodKey ──

    @Test
    fun `standardQuarterBounds returns the correct discrete range for each quarter`() {
        assertEquals("2026-04-06" to "2026-07-05", standardQuarterBounds("2026-27", "#001"))
        assertEquals("2026-07-06" to "2026-10-05", standardQuarterBounds("2026-27", "#002"))
        assertEquals("2026-10-06" to "2027-01-05", standardQuarterBounds("2026-27", "#003"))
        assertEquals("2027-01-06" to "2027-04-05", standardQuarterBounds("2026-27", "#004"))
    }

    @Test
    fun `standardQuarterBounds returns null for an unrecognised period key`() {
        assertNull(standardQuarterBounds("2026-27", "#0915"))
    }

    @Test
    fun `standardQuarterBounds returns null for a malformed tax year`() {
        assertNull(standardQuarterBounds("not-a-year", "#001"))
    }

    @Test
    fun `standardQuarterBounds and standardQuarterFor agree on the same quarter`() {
        val fromDate = standardQuarterFor("2026-08-01")
        val bounds   = standardQuarterBounds(fromDate.taxYear, fromDate.periodKey)
        assertEquals(fromDate.startDate to fromDate.endDate, bounds)
    }

    // ── getOrCreateStandard ────────────────────────────────────────────────

    @Test
    fun `getOrCreateStandard creates the period when none exists`() {
        val period = PeriodRepository.getOrCreateStandard("2026-05-01")

        assertEquals("2026-27", period.taxYear)
        assertEquals("#001", period.periodKey)
        assertEquals(period, PeriodRepository.findById(period.id))
    }

    @Test
    fun `getOrCreateStandard returns the same row for two dates in the same quarter`() {
        val first  = PeriodRepository.getOrCreateStandard("2026-04-06")
        val second = PeriodRepository.getOrCreateStandard("2026-07-05")

        assertEquals(first.id, second.id)
        assertEquals(1, PeriodRepository.findByTaxYear("2026-27").size)
    }

    @Test
    fun `getOrCreateStandard gives different rows for different quarters`() {
        val q1 = PeriodRepository.getOrCreateStandard("2026-05-01")
        val q2 = PeriodRepository.getOrCreateStandard("2026-08-01")

        assertEquals(false, q1.id == q2.id)
    }

    @Test
    fun `getOrCreateStandard does not overwrite an existing row's dates`() {
        // Simulates a row previously stored from HMRC's obligations.
        val fromHmrc = PeriodRepository.upsert(
            "2026-27", "#001", "2026-04-06", "2026-07-05", "2026-08-06",
        )

        val found = PeriodRepository.getOrCreateStandard("2026-05-01")

        assertEquals(fromHmrc.id, found.id)
        assertEquals("2026-08-06", found.dueDate)
        assertEquals("2026-08-06", PeriodRepository.findById(fromHmrc.id)?.dueDate)
    }
}
