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

package org.charlesatkinson.libremtd.ui

import javafx.scene.Node
import javafx.scene.control.Label
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension

/**
 * Tests for discardCachedPanes and the destination sets, which exist so that
 * changes made behind the cached panes' backs (a spreadsheet import, or a
 * change in the Properties pane) are followed by those panes being rebuilt
 * from the database on their next visit rather than showing a stale copy.
 *
 * @ExtendWith is needed because Label() requires the JavaFX toolkit.
 */
@ExtendWith(ApplicationExtension::class)
class MainWindowPaneInvalidationTest {

    private enum class TestDest { A, B, C }

    @Test
    fun `only the listed destinations are discarded`() {
        val a = Label("a")
        val b = Label("b")
        val c = Label("c")
        val cache = mutableMapOf<TestDest, Node>(TestDest.A to a, TestDest.B to b, TestDest.C to c)

        discardCachedPanes(cache, setOf(TestDest.A, TestDest.C))

        assertEquals(setOf(TestDest.B), cache.keys)
        assertSame(b, cache[TestDest.B])
    }

    @Test
    fun `discarding a destination that is not cached does nothing`() {
        val cache = mutableMapOf<TestDest, Node>(TestDest.A to Label("a"))

        discardCachedPanes(cache, setOf(TestDest.B))

        assertEquals(setOf(TestDest.A), cache.keys)
    }

    @Test
    fun `a discarded pane is rebuilt on its next visit`() {
        var buildCount = 0
        val cache = mutableMapOf<NavDestination, Node>()
        val build: (NavDestination) -> Node = { buildCount++; Label("built $buildCount") }

        resolvePane(NavDestination.ExpensesPropertyUk, emptySet(), cache, build)
        discardCachedPanes(cache, ENTRY_DESTINATIONS)
        resolvePane(NavDestination.ExpensesPropertyUk, emptySet(), cache, build)

        assertEquals(2, buildCount)
    }

    @Test
    fun `the entry destinations are the six panes that show entries`() {
        assertEquals(
            setOf(
                NavDestination.DividendIncome,
                NavDestination.IncomePropertyUk,
                NavDestination.IncomePropertyForeign,
                NavDestination.SavingsIncome,
                NavDestination.ExpensesPropertyUk,
                NavDestination.ExpensesPropertyForeign,
            ),
            ENTRY_DESTINATIONS,
        )
    }

    @Test
    fun `panes that do not show entries are not discarded`() {
        assertFalse(NavDestination.Settings in ENTRY_DESTINATIONS)
        assertFalse(NavDestination.Submissions in ENTRY_DESTINATIONS)
        assertFalse(NavDestination.Properties in ENTRY_DESTINATIONS)
        assertTrue(ENTRY_DESTINATIONS.isNotEmpty())
    }

    @Test
    fun `the property dependent destinations are the four property panes and the export pane`() {
        assertEquals(
            setOf(
                NavDestination.IncomePropertyUk,
                NavDestination.IncomePropertyForeign,
                NavDestination.ExpensesPropertyUk,
                NavDestination.ExpensesPropertyForeign,
                NavDestination.ExportSpreadsheet,
            ),
            PROPERTY_DEPENDENT_DESTINATIONS,
        )
    }

    @Test
    fun `panes without a property selector are not discarded when properties change`() {
        assertFalse(NavDestination.DividendIncome in PROPERTY_DEPENDENT_DESTINATIONS)
        assertFalse(NavDestination.SavingsIncome in PROPERTY_DEPENDENT_DESTINATIONS)
        assertFalse(NavDestination.Properties in PROPERTY_DEPENDENT_DESTINATIONS)
    }

    @Test
    fun `discarding the property dependent panes leaves the others cached`() {
        val dividends = Label("dividends")
        val cache = mutableMapOf<NavDestination, Node>(
            NavDestination.DividendIncome to dividends,
            NavDestination.IncomePropertyUk to Label("income"),
            NavDestination.ExportSpreadsheet to Label("export"),
        )

        discardCachedPanes(cache, PROPERTY_DEPENDENT_DESTINATIONS)

        assertEquals(setOf(NavDestination.DividendIncome), cache.keys)
        assertSame(dividends, cache[NavDestination.DividendIncome])
    }
}
