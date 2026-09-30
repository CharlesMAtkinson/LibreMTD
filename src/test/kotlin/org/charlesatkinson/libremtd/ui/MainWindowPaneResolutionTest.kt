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

package org.charlesatkinson.libremtd.ui

import javafx.scene.Node
import javafx.scene.control.Label
import org.charlesatkinson.libremtd.ui.components.RefreshableRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension

/**
 * Unit tests for [resolvePane], extracted from MainWindow.navigateTo()
 * specifically so this caching/refresh behaviour can be tested without a
 * live Stage/Scene. These exist to pin down the bug where a cached pane's
 * PeriodSelector never reloaded newly-fetched periods on a later visit —
 * see the development log entry on "No periods yet" persisting after a
 * Submissions visit for the full report.
 *
 * @ExtendWith is still needed because RefreshableRoot extends VBox and
 * Label() requires the JavaFX toolkit to be initialised, even though no
 * Stage is shown.
 */
@ExtendWith(ApplicationExtension::class)
class MainWindowPaneResolutionTest {

    private enum class TestDest { Refreshable, PlainCached, AlwaysFresh }

    @Test
    fun `an uncached destination is rebuilt on every visit`() {
        var buildCount = 0
        val cache = mutableMapOf<TestDest, Node>()
        val build: (TestDest) -> Node = { buildCount++; Label("built $buildCount") }

        resolvePane(TestDest.AlwaysFresh, setOf(TestDest.AlwaysFresh), cache, build)
        resolvePane(TestDest.AlwaysFresh, setOf(TestDest.AlwaysFresh), cache, build)

        assertEquals(2, buildCount)
        assertEquals(0, cache.size)
    }

    @Test
    fun `a cached non-RefreshableRoot pane is built once and reused unchanged`() {
        var buildCount = 0
        val cache = mutableMapOf<TestDest, Node>()
        val build: (TestDest) -> Node = { buildCount++; Label("built $buildCount") }

        val first  = resolvePane(TestDest.PlainCached, emptySet(), cache, build)
        val second = resolvePane(TestDest.PlainCached, emptySet(), cache, build)

        assertEquals(1, buildCount)
        assertSame(first, second)
    }

    @Test
    fun `revisiting a cached RefreshableRoot pane calls refresh, but the first visit does not`() {
        var refreshCount = 0
        val cache = mutableMapOf<TestDest, Node>()
        val build: (TestDest) -> Node = { RefreshableRoot(Label("content")) { refreshCount++ } }

        resolvePane(TestDest.Refreshable, emptySet(), cache, build)
        assertEquals(0, refreshCount, "First visit builds fresh — its own construction already loaded current data")

        resolvePane(TestDest.Refreshable, emptySet(), cache, build)
        assertEquals(1, refreshCount, "Second visit should refresh the cached pane")

        resolvePane(TestDest.Refreshable, emptySet(), cache, build)
        assertEquals(2, refreshCount, "Each subsequent revisit should refresh again")
    }
}
