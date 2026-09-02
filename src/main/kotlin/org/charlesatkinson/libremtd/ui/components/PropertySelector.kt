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

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.*
import org.charlesatkinson.libremtd.database.Property
import org.charlesatkinson.libremtd.database.PropertyRepository
import org.charlesatkinson.libremtd.database.PropertyType
import org.charlesatkinson.libremtd.ui.components.UiPreferences

class PropertySelector(
    private val userId: Int,
    private val propertyType: PropertyType? = null,
    private val onSelectionChanged: (Property?) -> Unit,
) {
    private val prefs = UiPreferences(userId)
    val root: HBox

    private val propertyPicker = ComboBox<Property>()
    private lateinit var endedHintLabel: Label

    var selectedProperty: Property? = null
        private set

    init {
        root = buildUI()
        loadProperties()
    }

    private fun buildUI(): HBox {
        propertyPicker.apply {
            promptText = "Select property"
            maxWidth   = Double.MAX_VALUE
            buttonCell = propertyCell()
            setCellFactory { propertyCell() }
            setOnAction {
                selectedProperty = value
                prefs.lastPropertyId = value?.id
                updateEndedHint(value)
                onSelectionChanged(value)
            }
        }
        HBox.setHgrow(propertyPicker, Priority.ALWAYS)

        endedHintLabel = hintLabel("").apply {
            isVisible = false
            isManaged = false
        }

        return HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            padding   = Insets(0.0, 0.0, 4.0, 0.0)
            children.addAll(
                wrappingLabel("Property:").apply { minWidth = 70.0 },
                propertyPicker,
                endedHintLabel,
            )
        }
    }

    private fun updateEndedHint(property: Property?) {
        val endedAt = property?.endedAt
        if (endedAt == null) {
            endedHintLabel.isVisible = false
            endedHintLabel.isManaged = false
        } else {
            endedHintLabel.text = "Ended ${endedAt.take(10)} — entries remain available for review and amendment."
            endedHintLabel.isVisible = true
            endedHintLabel.isManaged = true
        }
    }

    private fun loadProperties() {
        val properties = PropertyRepository.findByUser(userId)
            .let { all -> if (propertyType != null) all.filter { it.propertyType == propertyType } else all }
            // Active properties first, then ended ones, each alphabetically —
            // ended properties stay selectable for review rather than
            // disappearing, but shouldn't clutter the top of the list.
            .sortedWith(compareBy({ it.endedAt != null }, { it.address }))
        propertyPicker.items.setAll(properties)

        when {
            properties.size == 1 -> {
                propertyPicker.value     = properties.first()
                propertyPicker.isDisable = true
                selectedProperty         = properties.first()
                updateEndedHint(properties.first())
                onSelectionChanged(properties.first())
            }
            properties.size > 1 -> {
                val lastId = prefs.lastPropertyId
                val restored = if (lastId != null) properties.firstOrNull { it.id == lastId } else null
                if (restored != null) {
                    propertyPicker.value = restored
                    selectedProperty     = restored
                    updateEndedHint(restored)
                    onSelectionChanged(restored)
                }
            }
        }
    }

    private fun propertyCell() = object : ListCell<Property>() {
        override fun updateItem(item: Property?, empty: Boolean) {
            super.updateItem(item, empty)
            if (empty || item == null) {
                text = null
                style = ""
                return
            }
            val locationSuffix = when (item.propertyType) {
                PropertyType.UK      -> item.postcode
                PropertyType.FOREIGN -> item.countryCode
            }
            text = if (item.endedAt != null)
                "${item.address}, $locationSuffix (ended)"
            else
                "${item.address}, $locationSuffix"
            style = if (item.endedAt != null) "-fx-opacity: 0.6;" else ""
        }
    }
}

