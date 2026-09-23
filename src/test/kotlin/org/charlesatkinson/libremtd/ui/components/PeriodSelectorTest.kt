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

package org.charlesatkinson.libremtd.ui.components

import javafx.scene.Scene
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.charlesatkinson.libremtd.database.Period
import org.charlesatkinson.libremtd.database.PeriodRepository
import org.charlesatkinson.libremtd.database.tables.Periods
import org.charlesatkinson.libremtd.database.tables.UiPreferences as UiPreferencesTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

/**
 * Runs against a real JavaFX display — see ReadOnlyLabelsTest's class doc
 * comment for the project's current agreement on this.
 */
@ExtendWith(ApplicationExtension::class)
class PeriodSelectorTest {

    private lateinit var stage: Stage
    private lateinit var db: Database
    private lateinit var dbFile: Path

    @Suppress("unused")
    @Start
    private fun start(stage: Stage) {
        this.stage = stage
        stage.show()
    }

    @BeforeEach
    fun setUp() {
        // Real temp-file SQLite, not in-memory — see SettingsRepositoryTest
        // for why. Both tables are needed: PeriodSelector reads periods,
        // and reads/writes the user's last-selected period via UiPreferences.
        dbFile = Files.createTempFile("libremtd-test-", ".sqlite")
        db = Database.connect("jdbc:sqlite:${dbFile}", driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(Periods, UiPreferencesTable)
        }
    }

    @AfterEach
    fun tearDown() {
        transaction(db) {
            SchemaUtils.drop(Periods, UiPreferencesTable)
        }
        dbFile.deleteIfExists()
    }

    private fun addPeriod(
        periodKey: String = "#001",
        startDate: String = "2026-04-06",
        endDate: String = "2026-07-05",
        dueDate: String = "2026-08-05",
    ): Period = PeriodRepository.upsert("2026-27", periodKey, startDate, endDate, dueDate)

    private fun comboBoxIn(selector: PeriodSelector): ComboBox<*> =
        selector.root.children.filterIsInstance<ComboBox<*>>().single()

    private fun noPeriodsHintTextIn(selector: PeriodSelector): String? =
        selector.root.children.filterIsInstance<Label>()
            .map { it.text }
            .firstOrNull { it.contains("fetch obligations", ignoreCase = true) }

    @Test
    fun `with no periods, selectedPeriod is null and the picker is disabled`(robot: FxRobot) {
        lateinit var selector: PeriodSelector
        robot.interact {
            selector = PeriodSelector(userId = 1) {}
            stage.scene = Scene(VBox(selector.root), 400.0, 100.0)
        }

        assertNull(selector.selectedPeriod)
        assertTrue(comboBoxIn(selector).isDisable)
    }

    @Test
    fun `with no periods, a hint explaining why is shown`(robot: FxRobot) {
        lateinit var selector: PeriodSelector
        robot.interact {
            selector = PeriodSelector(userId = 1) {}
            stage.scene = Scene(VBox(selector.root), 400.0, 100.0)
        }

        assertNotNull(noPeriodsHintTextIn(selector))
    }

    @Test
    fun `with one period, it is auto-selected, the picker is enabled, and no hint is shown`(robot: FxRobot) {
        val period = addPeriod()

        lateinit var selector: PeriodSelector
        robot.interact {
            selector = PeriodSelector(userId = 1) {}
            stage.scene = Scene(VBox(selector.root), 400.0, 100.0)
        }

        assertEquals(period, selector.selectedPeriod)
        assertFalse(comboBoxIn(selector).isDisable)
        assertNull(noPeriodsHintTextIn(selector))
    }

    @Test
    fun `a previously selected period is restored when the selector is rebuilt`(robot: FxRobot) {
        addPeriod(periodKey = "#001", startDate = "2026-04-06", endDate = "2026-07-05", dueDate = "2026-08-05")
        val p2 = addPeriod(periodKey = "#002", startDate = "2026-07-06", endDate = "2026-10-05", dueDate = "2026-11-05")
        UiPreferences(1).lastPeriodId = p2.id

        lateinit var selector: PeriodSelector
        robot.interact {
            selector = PeriodSelector(userId = 1) {}
            stage.scene = Scene(VBox(selector.root), 400.0, 100.0)
        }

        assertEquals(p2, selector.selectedPeriod)
    }

    @Test
    fun `reload picks up periods added after construction, and enables a previously disabled picker`(robot: FxRobot) {
        lateinit var selector: PeriodSelector
        robot.interact {
            selector = PeriodSelector(userId = 1) {}
            stage.scene = Scene(VBox(selector.root), 400.0, 100.0)
        }

        // Nothing existed at construction time — this reproduces the "fresh
        // database, not yet connected to HMRC" state.
        assertTrue(comboBoxIn(selector).isDisable)

        // Simulates ObligationsSection.refresh() populating Periods after a
        // successful HMRC connection, which is what MainWindow.notifyConnected()
        // is reacting to when it calls the hosting pane's refresh().
        val period = addPeriod()

        robot.interact { selector.reload() }

        assertEquals(period, selector.selectedPeriod)
        assertFalse(comboBoxIn(selector).isDisable)
        assertNull(noPeriodsHintTextIn(selector))
    }
}
