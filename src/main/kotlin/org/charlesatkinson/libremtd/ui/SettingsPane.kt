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

import javafx.application.Platform
import javafx.beans.binding.Bindings
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.charlesatkinson.libremtd.database.HmrcSettings
import org.charlesatkinson.libremtd.database.SettingsRepository
import org.charlesatkinson.libremtd.network.BusinessDetailsClient
import org.charlesatkinson.libremtd.network.ClientContext
import org.charlesatkinson.libremtd.network.HmrcApiClient
import org.charlesatkinson.libremtd.security.FraudPreventionHeaders
import org.charlesatkinson.libremtd.security.OAuth2Handler
import org.charlesatkinson.libremtd.security.TokenStore
import org.charlesatkinson.libremtd.ui.components.Dialogs
import org.charlesatkinson.libremtd.utils.ApiResult
import org.charlesatkinson.libremtd.utils.Config
import org.charlesatkinson.libremtd.ui.components.UiPreferences
import org.charlesatkinson.libremtd.ui.components.wrappingLabel

private val logger = KotlinLogging.logger {}

class SettingsPane(
    private val scope:              CoroutineScope,
    private val userId:             Int,
    private val settingsRepository: SettingsRepository = SettingsRepository(),
    private val onStatusChange:     (String) -> Unit,
    private val onSettingsSaved:    () -> Unit = {},
) {
    private val prefs = UiPreferences(userId)
    val root: VBox

    private val ninoField         = TextField()
    private val utrField          = TextField()
    private val businessIdUkField   = TextField().apply {
        promptText = "Fetched automatically from HMRC"
        prefWidth  = 260.0
        isEditable = false
        styleClass.add("readonly-field")
    }
    private val businessIdForeignField = TextField().apply {
        promptText = "Fetched automatically from HMRC"
        prefWidth  = 260.0
        isEditable = false
        styleClass.add("readonly-field")
    }
    private val fullNameField     = TextField()
    private val dateOfBirthField  = TextField()
    private val addressLine1Field = TextField()
    private val addressLine2Field = TextField()
    private val addressLine3Field = TextField()
    private val postcodeField     = TextField()

    private val clientIdField     = TextField()
    private val clientSecretField = PasswordField()

    private val saveBtn = Button("Save settings").apply {
        styleClass.add("primary-action-button")
        setOnAction { handleSave() }
    }

    private data class SettingsSnapshot(
        val nino: String, val utr: String, val businessIdUk: String, val businessIdForeign: String,
        val fullName: String, val dob: String,
        val line1: String, val line2: String, val line3: String, val postcode: String,
        val clientId: String, val clientSecret: String,
    )

    private var savedSnapshot = emptySnapshot()

    init {
        root = buildUI()
        bindSaveButton()
        loadSettings()
    }

    private fun emptySnapshot() = SettingsSnapshot("","","","","","","","","","","","")

    private fun currentSnapshot() = SettingsSnapshot(
        nino              = ninoField.text.trim(),
        utr               = utrField.text.trim(),
        businessIdUk      = businessIdUkField.text.trim(),
        businessIdForeign = businessIdForeignField.text.trim(),
        fullName          = fullNameField.text.trim(),
        dob               = dateOfBirthField.text.trim(),
        line1             = addressLine1Field.text.trim(),
        line2             = addressLine2Field.text.trim(),
        line3             = addressLine3Field.text.trim(),
        postcode          = postcodeField.text.trim(),
        clientId          = clientIdField.text.trim(),
        clientSecret      = clientSecretField.text.trim(),
    )

    private fun bindSaveButton() {
        val isDirty = Bindings.createBooleanBinding(
            { currentSnapshot() != savedSnapshot },
            ninoField.textProperty(),
            utrField.textProperty(),
            fullNameField.textProperty(),
            dateOfBirthField.textProperty(),
            addressLine1Field.textProperty(),
            addressLine2Field.textProperty(),
            addressLine3Field.textProperty(),
            postcodeField.textProperty(),
            clientIdField.textProperty(),
            clientSecretField.textProperty(),
            businessIdUkField.textProperty(),
            businessIdForeignField.textProperty(),
        )
        saveBtn.disableProperty().bind(isDirty.not())
    }

    private fun buildUI(): VBox {
        return VBox(20.0).apply {
            padding = Insets(4.0)
            children.addAll(
                wrappingLabel("Settings").apply {
                    style = "-fx-font-size: 22px; -fx-font-weight: bold;"
                },
                buildYourDetailsSection(),
                buildDeveloperCredentialsSection(),
                buildSaveButtonRow(),
            )
        }
    }

    private fun buildYourDetailsSection(): VBox {
        ninoField.apply {
            promptText = "e.g. QQ123456C"
            prefWidth  = 160.0
        }
        utrField.apply {
            promptText = "10-digit Unique Taxpayer Reference"
            prefWidth  = 220.0
        }
        fullNameField.apply {
            promptText = "Full legal name"
            prefWidth  = 280.0
        }
        dateOfBirthField.apply {
            promptText = "YYYY-MM-DD"
            prefWidth  = 140.0
        }
        addressLine1Field.apply {
            promptText = "Address line 1"
            prefWidth  = 300.0
        }
        addressLine2Field.apply {
            promptText = "Address line 2 (optional)"
            prefWidth  = 300.0
        }
        addressLine3Field.apply {
            promptText = "Town / city"
            prefWidth  = 300.0
        }
        postcodeField.apply {
            promptText = "Postcode"
            prefWidth  = 120.0
        }

        val fetchBusinessIdUkBtn = Button("Fetch from HMRC").apply {
            styleClass.add("primary-action-button")
            setOnAction { handleFetchBusinessIdUk() }
        }

        val fetchForeignBusinessIdBtn = Button("Fetch from HMRC").apply {
            styleClass.add("primary-action-button")
            setOnAction { handleFetchForeignBusinessId() }
        }

        return buildSection(
            title = "Your details",
            hint  = "Personal details required for HMRC API calls",
            rows  = listOf(
                buildRow("Full name",                        fullNameField),
                buildRow("Date of birth",                    dateOfBirthField),
                buildRow("Address line 1",                   addressLine1Field),
                buildRow("Address line 2",                   addressLine2Field),
                buildRow("Town / city",                      addressLine3Field),
                buildRow("Postcode",                         postcodeField),
                buildRow("National Insurance number (NINO)", ninoField),
                buildRow("Self Assessment UTR",              utrField),
                buildRow("UK property business ID", HBox(8.0).apply {
                    children.addAll(businessIdUkField, fetchBusinessIdUkBtn)
                }),
                buildRow("Foreign property business ID", HBox(8.0).apply {
                    children.addAll(businessIdForeignField, fetchForeignBusinessIdBtn)
                }),
            ),
        )
    }

    private fun buildDeveloperCredentialsSection(): VBox {
        clientIdField.apply {
            promptText = "HMRC Developer Hub Client ID"
            prefWidth  = 340.0
        }
        clientSecretField.apply {
            promptText = "Client Secret"
            prefWidth  = 340.0
        }

        return buildSection(
            title = "HMRC Developer credentials",
            hint  = "Sandbox / developer use only — not required in production",
            rows  = listOf(
                buildRow("Client ID",     clientIdField),
                buildRow("Client Secret", clientSecretField),
            ),
        )
    }

    private fun buildSaveButtonRow(): HBox {
        return HBox(saveBtn).apply {
            alignment = Pos.CENTER_LEFT
        }
    }

    private fun handleSave() {
        val nino              = ninoField.text.trim()
        val utr               = utrField.text.trim()
        val businessIdUk      = businessIdUkField.text.trim()
        val businessIdForeign = businessIdForeignField.text.trim()
        val fullName          = fullNameField.text.trim()
        val dob               = dateOfBirthField.text.trim()
        val line1             = addressLine1Field.text.trim()
        val line2             = addressLine2Field.text.trim()
        val line3             = addressLine3Field.text.trim()
        val postcode          = postcodeField.text.trim()
        val clientId          = clientIdField.text.trim()
        val clientSecret      = clientSecretField.text.trim()

        if (nino.isBlank()) {
            Dialogs.showError("Please enter your National Insurance number.")
            return
        }
        if (dob.isNotBlank() && !dob.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) {
            Dialogs.showError("Date of birth must be in format YYYY-MM-DD.")
            return
        }

        val settings = HmrcSettings(
            userId            = userId,
            clientId          = clientId,
            clientSecret      = clientSecret,
            nino              = nino,
            utr               = utr,
            businessIdUk      = businessIdUk,
            businessIdForeign = businessIdForeign,
            fullName          = fullName,
            dateOfBirth       = dob,
            addressLine1      = line1,
            addressLine2      = line2,
            addressLine3      = line3,
            postcode          = postcode,
        )

        scope.launch(Dispatchers.IO) {
            settingsRepository.save(settings)
            withContext(Dispatchers.JavaFx) {
                savedSnapshot = currentSnapshot()
                onStatusChange("Settings saved ✓")
                onSettingsSaved()
            }
        }
    }

    private fun handleFetchBusinessIdUk() {
        val nino = ninoField.text.trim()
        if (nino.isBlank()) {
            Dialogs.showError("Enter your NINO first.")
            return
        }

        if (TokenStore.isExpired() || TokenStore.getAccessToken() == null) {
            Dialogs.showError("Connect to HMRC first via HMRC Connect.")
            return
        }

        scope.launch {
            val settings = withContext(Dispatchers.IO) {
                settingsRepository.load(userId)
            }

            if (settings == null || settings.clientId.isBlank()) {
                Platform.runLater { Dialogs.showError("Save your Client ID and Secret first.") }
                return@launch
            }

            val client = HmrcApiClient(
                libreMtdUserId = userId,
                isSandbox      = Config.hmrcSandbox,
                oauth2Handler  = OAuth2Handler(
                    clientId     = settings.clientId,
                    clientSecret = settings.clientSecret,
                    isSandbox    = Config.hmrcSandbox,
                    prefs = prefs
                ),
                fraudHeaders   = FraudPreventionHeaders(),
            )

            val result = BusinessDetailsClient(client).fetchUkPropertyBusinessId(
                nino         = nino,
                context      = ClientContext(800, 600),
                testScenario = if (Config.hmrcSandbox) "STATEFUL" else null,
            )

            Platform.runLater {
                when (result) {
                    is ApiResult.Success -> {
                        businessIdUkField.text = result.data
                        scope.launch(Dispatchers.IO) {
                            settings.copy(businessIdUk = result.data)
                                .also { settingsRepository.save(it) }
                            withContext(Dispatchers.JavaFx) {
                                savedSnapshot = currentSnapshot()
                            }
                        }
                        onStatusChange("Business ID fetched and saved: ${result.data} ✓")
                    }
                    is ApiResult.Failure -> {
                        Dialogs.showError(result.message, title = "Fetch Business ID Failed")
                    }
                }
            }
        }
    }

    private fun handleFetchForeignBusinessId() {
        val nino = ninoField.text.trim()
        if (nino.isBlank()) {
            Dialogs.showError("Enter your NINO first.")
            return
        }

        if (TokenStore.isExpired() || TokenStore.getAccessToken() == null) {
            Dialogs.showError("Connect to HMRC first via HMRC Connect.")
            return
        }

        scope.launch {
            val settings = withContext(Dispatchers.IO) {
                settingsRepository.load(userId)
            }

            if (settings == null || settings.clientId.isBlank()) {
                Platform.runLater { Dialogs.showError("Save your Client ID and Secret first.") }
                return@launch
            }

            val client = HmrcApiClient(
                libreMtdUserId = userId,
                isSandbox      = Config.hmrcSandbox,
                oauth2Handler  = OAuth2Handler(
                    clientId     = settings.clientId,
                    clientSecret = settings.clientSecret,
                    isSandbox    = Config.hmrcSandbox,
                    prefs = prefs
                ),
                fraudHeaders   = FraudPreventionHeaders(),
            )

            val result = BusinessDetailsClient(client).fetchForeignPropertyBusinessId(
                nino         = nino,
                context      = ClientContext(800, 600),
                testScenario = if (Config.hmrcSandbox) "STATEFUL" else null,
            )

            Platform.runLater {
                when (result) {
                    is ApiResult.Success -> {
                        businessIdForeignField.text = result.data
                        scope.launch(Dispatchers.IO) {
                            settings.copy(businessIdForeign = result.data)
                                .also { settingsRepository.save(it) }
                            withContext(Dispatchers.JavaFx) {
                                savedSnapshot = currentSnapshot()
                            }
                        }
                        onStatusChange("Foreign property business ID fetched and saved: ${result.data} ✓")
                    }
                    is ApiResult.Failure -> {
                        Dialogs.showError(result.message, title = "Fetch Foreign Business ID Failed")
                    }
                }
            }
        }
    }

    private fun loadSettings() {
        scope.launch(Dispatchers.IO) {
            val settings = settingsRepository.load(userId)
            withContext(Dispatchers.JavaFx) {
                settings?.let { s ->
                    ninoField.text              = s.nino
                    utrField.text                = s.utr
                    businessIdUkField.text       = s.businessIdUk
                    businessIdForeignField.text  = s.businessIdForeign
                    fullNameField.text           = s.fullName
                    dateOfBirthField.text        = s.dateOfBirth
                    addressLine1Field.text       = s.addressLine1
                    addressLine2Field.text       = s.addressLine2
                    addressLine3Field.text       = s.addressLine3
                    postcodeField.text           = s.postcode
                    clientIdField.text           = s.clientId
                    clientSecretField.text       = s.clientSecret
                }
                savedSnapshot = currentSnapshot()
            }
        }
    }

    private fun buildSection(
        title: String,
        hint:  String,
        rows:  List<javafx.scene.Node>,
    ): VBox {
        return VBox(8.0).apply {
            padding = Insets(12.0, 16.0, 12.0, 16.0)
            styleClass.add("content-card")
            style   = "-fx-border-radius: 8; -fx-background-radius: 8;"
            children.addAll(
                VBox(2.0).apply {
                    children.addAll(
                        wrappingLabel(title).apply {
                            style = "-fx-font-size: 15px; -fx-font-weight: bold;"
                        },
                        wrappingLabel(hint).apply {
                            styleClass.add("hint-label")
                            style = "-fx-font-size: 11px;"
                        },
                    )
                },
                Separator(),
                *rows.toTypedArray(),
            )
        }
    }

    private fun buildRow(labelText: String, field: Control): HBox =
        buildRow(labelText, field as javafx.scene.Node)

    private fun buildRow(labelText: String, node: javafx.scene.Node): HBox {
        return HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(
                wrappingLabel(labelText).apply {
                    prefWidth = 240.0
                    styleClass.add("row-label")
                },
                node,
            )
        }
    }
}
