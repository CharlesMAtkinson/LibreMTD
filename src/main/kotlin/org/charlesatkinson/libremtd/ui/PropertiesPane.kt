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
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.collections.FXCollections
import javafx.beans.property.SimpleStringProperty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import org.charlesatkinson.libremtd.database.Property
import org.charlesatkinson.libremtd.database.PropertyRepository
import org.charlesatkinson.libremtd.ui.components.wrappingLabel

private val UK_POSTCODE_REGEX = Regex("^[A-Z]{1,2}[0-9][0-9A-Z]? ?[0-9][A-Z]{2}$")

class PropertiesPane(
    private val scope: CoroutineScope,
    private val userId: Int,
    private val onStatusChange: (String) -> Unit,
) {

    val root: VBox

    private val properties = FXCollections.observableArrayList<Property>()

    private val addressField  = TextField()
    private val postcodeField = TextField()

    init {
        root = buildUI()
        loadProperties()
    }

    private fun buildUI(): VBox {
        return VBox(16.0).apply {
            padding = Insets(4.0)
            children.addAll(
                wrappingLabel("Properties").apply {
                    style = "-fx-font-size: 22px; -fx-font-weight: bold;"
                },
                wrappingLabel(
                    "Manage your let properties. Income and expenses are recorded per property " +
                            "and aggregated across all properties when submitting to HMRC."
                ).apply {
                    styleClass.add("hint-label")
                },
                buildEntryForm(),
                buildPropertiesTable(),
            )
        }
    }

    private fun buildEntryForm(): VBox {
        addressField.apply {
            promptText = "Address"
            maxWidth   = Double.MAX_VALUE
        }
        HBox.setHgrow(addressField, Priority.ALWAYS)

        postcodeField.apply {
            promptText = "Postcode"
            prefWidth  = 120.0
            minWidth   = 120.0
            maxWidth   = 120.0
        }

        val addBtn = Button("Add property").apply {
            styleClass.add("primary-action-button")
            setOnAction { handleAdd() }
        }

        val addressLabel  = Label("Address:")
        val postcodeLabel = Label("Postcode:")
        addressLabel.minWidth  = Region.USE_PREF_SIZE
        postcodeLabel.minWidth = Region.USE_PREF_SIZE

        return VBox(8.0).apply {
            padding = Insets(12.0, 16.0, 12.0, 16.0)
            styleClass.add("content-card")
            style   = "-fx-border-radius: 8; -fx-background-radius: 8;"
            children.addAll(
                wrappingLabel("New property").apply { style = "-fx-font-weight: bold;" },
                Separator(),
                HBox(10.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(addressLabel, addressField, postcodeLabel, postcodeField, addBtn)
                },
            )
        }
    }

    private fun handleAdd() {
        val address  = addressField.text.trim()
        val postcode = postcodeField.text.trim().uppercase()

        when {
            address.isBlank()  -> showError("Please enter an address.")
            postcode.isBlank() -> showError("Please enter a postcode.")
            !UK_POSTCODE_REGEX.matches(postcode) ->
                showError("Please enter a valid UK postcode (e.g. SW1A 1AA).")
            else -> {
                scope.launch(Dispatchers.IO) {
                    val property = PropertyRepository.create(
                        userId   = userId,
                        address  = address,
                        postcode = postcode,
                    )
                    kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                        properties.add(property)
                        clearForm()
                        onStatusChange("Property added ✓")
                    }
                }
            }
        }
    }

    private fun buildPropertiesTable(): VBox {
        val table = TableView<Property>(properties).apply {
            prefHeight  = 300.0
            placeholder = wrappingLabel("No properties added yet")
            columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
            columns.addAll(
                TableColumn<Property, String>("Address").apply {
                    setCellValueFactory { SimpleStringProperty(it.value.address) }
                },
                TableColumn<Property, String>("Postcode").apply {
                    prefWidth = 120.0
                    maxWidth  = 140.0
                    minWidth  = 100.0
                    setCellValueFactory { SimpleStringProperty(it.value.postcode) }
                },
                TableColumn<Property, String>("Added").apply {
                    prefWidth = 120.0
                    maxWidth  = 140.0
                    minWidth  = 100.0
                    setCellValueFactory { SimpleStringProperty(it.value.createdAt.take(10)) }
                },
            )
        }

        val deleteBtn = Button("Delete selected").apply {
            styleClass.add("primary-action-button")
            setOnAction {
                val selected = table.selectionModel.selectedItem
                if (selected == null) {
                    showError("Please select a property to delete.")
                    return@setOnAction
                }

                // Confirm synchronously on the JavaFx thread before launching the coroutine.
                // This avoids any risk of the captured reference becoming stale inside
                // a nested coroutine continuation.
                val confirmed = Alert(Alert.AlertType.CONFIRMATION).apply {
                    title       = "Delete property"
                    headerText  = "Delete ${selected.address}?"
                    contentText = "This will mark the property as deleted. Existing income " +
                            "and expense entries for this property are retained."
                }.showAndWait().map { it.buttonData == ButtonBar.ButtonData.OK_DONE }.orElse(false)

                if (!confirmed) return@setOnAction

                scope.launch(Dispatchers.IO) {
                    PropertyRepository.softDelete(selected.id)
                    kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                        // Reload from the database rather than relying on list.remove(),
                        // so the displayed list is always consistent with persisted state.
                        loadProperties()
                        onStatusChange("Property deleted")
                    }
                }
            }
        }

        return VBox(8.0).apply {
            padding = Insets(12.0, 16.0, 12.0, 16.0)
            styleClass.add("content-card")
            style   = "-fx-border-radius: 8; -fx-background-radius: 8;"
            children.addAll(
                wrappingLabel("Your properties").apply { style = "-fx-font-weight: bold;" },
                Separator(),
                table,
                deleteBtn,
            )
        }
    }

    private fun loadProperties() {
        scope.launch(Dispatchers.IO) {
            val loaded = PropertyRepository.findByUser(userId)
            kotlinx.coroutines.withContext(Dispatchers.JavaFx) {
                properties.setAll(loaded)
                onStatusChange("${loaded.size} property/properties loaded")
            }
        }
    }

    private fun clearForm() {
        addressField.clear()
        postcodeField.clear()
    }

    private fun showError(msg: String) {
        Alert(Alert.AlertType.ERROR).apply {
            title       = "Validation error"
            headerText  = null
            contentText = msg
            showAndWait()
        }
    }
}