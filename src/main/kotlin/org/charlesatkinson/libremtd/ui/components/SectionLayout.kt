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

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.Separator
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox

private val STATUS_CLASSES = listOf("status-success", "status-error", "status-warning", "hint-label")

fun Label.setStatusStyle(styleClass: String) {
    this.styleClass.removeAll(STATUS_CLASSES)
    this.styleClass.add(styleClass)
}

fun buildSection(
    title:    String,
    infoText: String,
    rows:     List<javafx.scene.Node>,
): VBox = VBox(8.0).apply {
    maxWidth = Double.MAX_VALUE
    padding = Insets(12.0, 16.0, 12.0, 16.0)
    styleClass.add("content-card")
    style   = "-fx-border-radius: 8; -fx-background-radius: 8;"
    children.addAll(
        HBox(6.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(
                wrappingLabel(title).apply {
                    style = "-fx-font-size: 15px; -fx-font-weight: bold;"
                },
                infoPopup(infoText),
            )
        },
        Separator(),
        *rows.toTypedArray(),
    )
}

fun String.toObservable(): javafx.beans.value.ObservableValue<String> =
    javafx.beans.property.SimpleStringProperty(this)