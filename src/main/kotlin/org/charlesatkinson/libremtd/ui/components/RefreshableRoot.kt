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

package org.charlesatkinson.libremtd.ui.components

import javafx.scene.Node
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox

/**
 * Wraps a pane's built content so MainWindow's pane cache can call
 * [refresh] on any cached pane that needs to reload data not known at
 * construction time — typically periods or obligations fetched from HMRC,
 * which don't exist until after a successful connection — without
 * MainWindow needing to know each pane's concrete type.
 *
 * See MainWindow.notifyConnected(), which calls [refresh] on every cached
 * pane wrapped this way after a successful HMRC connection.
 *
 * Originally introduced only for SubmissionsPane, as a bespoke nested
 * class there; pulled out into this shared class when the same need arose
 * for the four income/expense panes that host a PeriodSelector, so there
 * is exactly one such mechanism rather than several near-identical ones.
 *
 * @param content  The pane's actual UI, built as normal.
 * @param onRefresh Called by [refresh]. Typically a hosting pane's own
 *                  `refresh()` method, e.g. `RefreshableRoot(content) { refresh() }`.
 */
class RefreshableRoot(content: Node, private val onRefresh: () -> Unit) : VBox() {
    init {
        children.add(content)
        VBox.setVgrow(content, Priority.ALWAYS)
    }

    fun refresh() = onRefresh()
}
