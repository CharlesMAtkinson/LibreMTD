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
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.layout.VBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.charlesatkinson.libremtd.database.SettingsRepository
import org.charlesatkinson.libremtd.security.OAuth2Handler
import org.charlesatkinson.libremtd.security.TokenStore
import org.charlesatkinson.libremtd.ui.components.UiPreferences
import org.charlesatkinson.libremtd.utils.Config
import org.charlesatkinson.libremtd.ui.components.wrappingLabel

class ConnectPane(
    private val scope:              CoroutineScope,
    private val userId:             Int,
    private val settingsRepository: SettingsRepository,
    private val onStatusChange:     (String) -> Unit,
    private val onMtdStatusChange:  (MtdConnectionStatus) -> Unit,
) {

    val root: VBox = buildUI()

    private fun buildUI(): VBox {
        val isConnected = !TokenStore.isExpired() && TokenStore.getAccessToken() != null
        onMtdStatusChange(
            if (isConnected) MtdConnectionStatus.Connected
            else             MtdConnectionStatus.Disconnected
        )

        lateinit var connectBtn:    Button
        lateinit var disconnectBtn: Button

        connectBtn = Button("Connect to HMRC (Authorise via OAuth 2.0)").apply {
            styleClass.add("primary-action-button")
            padding    = Insets(10.0, 20.0, 10.0, 20.0)
            isVisible  = !isConnected
            isManaged  = !isConnected
            setOnAction {
                handleConnect(
                    onSuccess = {
                        connectBtn.isVisible    = false
                        connectBtn.isManaged    = false
                        disconnectBtn.isVisible = true
                        disconnectBtn.isManaged = true
                    }
                )
            }
        }

        disconnectBtn = Button("Disconnect").apply {
            styleClass.add("danger-action-button")
            isVisible  = isConnected
            isManaged  = isConnected
            setOnAction {
                handleDisconnect()
                disconnectBtn.isVisible = false
                disconnectBtn.isManaged = false
                connectBtn.isVisible    = true
                connectBtn.isManaged    = true
            }
        }

        return VBox(16.0).apply {
            children.addAll(
                wrappingLabel("HMRC Connection").apply {
                    style = "-fx-font-size: 22px; -fx-font-weight: bold;"
                },
                wrappingLabel(
                    // https://developer.service.hmrc.gov.uk/api-documentation/docs/authorisation/user-restricted-endpoints
                    "LibreMTD uses OAuth 2.0 to connect securely to HMRC's Making Tax Digital APIs. " +
                            "Your credentials are never stored.  An access token is valid for four hours. " +
                            "For convenience it is kept in the database. " +
                            "To remove a valid access token from the database, click the Disconnect button."
                ).apply {
                    isWrapText = true
                    styleClass.add("hint-label")
                },
                connectBtn,
                disconnectBtn,
            )
        }
    }

    private fun handleConnect(onSuccess: () -> Unit = {}) {
        onMtdStatusChange(MtdConnectionStatus.Authenticating)
        onStatusChange("Opening HMRC authorisation…")

        scope.launch {
            val settings = withContext(Dispatchers.IO) {
                settingsRepository.load(userId)
            }

            if (settings == null || settings.clientId.isBlank()) {
                Platform.runLater {
                    onMtdStatusChange(MtdConnectionStatus.Error)
                    onStatusChange("Enter Client ID and Secret in Settings first")
                }
                return@launch
            }

            val handler = OAuth2Handler(
                clientId     = settings.clientId,
                clientSecret = settings.clientSecret,
                prefs        = UiPreferences(userId),
                isSandbox    = Config.hmrcSandbox,
            )

            val result = handler.authorize()

            Platform.runLater {
                if (result != null) {
                    TokenStore.store(result, userId)
                    onMtdStatusChange(MtdConnectionStatus.Connected)
                    onStatusChange("Connected to HMRC ✓")
                    onSuccess()
                } else {
                    onMtdStatusChange(MtdConnectionStatus.Error)
                    onStatusChange("HMRC authorisation failed or was cancelled")
                }
            }
        }
    }

    private fun handleDisconnect() {
        TokenStore.clear(userId)
        onMtdStatusChange(MtdConnectionStatus.Disconnected)
        onStatusChange("Disconnected from HMRC")
    }
}
