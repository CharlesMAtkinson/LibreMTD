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

import javafx.geometry.Insets
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import org.charlesatkinson.libremtd.ui.components.wrappingLabel

class DashboardPane {

    val root: VBox = buildUI()

    private fun buildUI(): VBox {
        val cards = HBox(16.0).apply {
            children.addAll(
                buildCard("Estimated tax",         "£ —",    "Submit quarterly updates to calculate"),
                buildCard("Next deadline",         "—",      "Load obligations to see upcoming dates"),
                buildCard("Submissions this year", "0 of 4", "Quarterly updates submitted to HMRC"),
            )
        }

        return VBox(20.0).apply {
            children.addAll(
                wrappingLabel("Dashboard").apply {
                    style = "-fx-font-size: 22px; -fx-font-weight: bold;"
                },
                cards,
            )
        }
    }

    private fun buildCard(title: String, body: String, hint: String): VBox =
        VBox(6.0).apply {
            padding   = Insets(14.0)
            prefWidth = 180.0
            styleClass.add("content-card")
            style     = "-fx-border-radius: 8; -fx-background-radius: 8;"
            children.addAll(
                wrappingLabel(title).apply { style = "-fx-font-size: 11px; -fx-font-weight: bold;" },
                wrappingLabel(body).apply  { style = "-fx-font-size: 24px; -fx-font-weight: bold;" },
                wrappingLabel(hint).apply  { style = "-fx-font-size: 11px;"; isWrapText = true },
            )
        }
}
