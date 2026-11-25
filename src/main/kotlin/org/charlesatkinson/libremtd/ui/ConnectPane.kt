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
import javafx.beans.value.ChangeListener
import javafx.beans.value.WeakChangeListener
import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.layout.VBox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.charlesatkinson.libremtd.database.SettingsRepository
import org.charlesatkinson.libremtd.security.AuthorisationWindow
import org.charlesatkinson.libremtd.security.OAuth2Handler
import org.charlesatkinson.libremtd.security.TokenStore
import org.charlesatkinson.libremtd.ui.components.UiPreferences
import org.charlesatkinson.libremtd.utils.Config
import org.charlesatkinson.libremtd.ui.components.hintLabel
import org.charlesatkinson.libremtd.ui.components.wrappingLabel

private val logger = KotlinLogging.logger {}

/** Shown while an authorisation window is open. */
internal const val AUTHORISATION_OPEN_HINT =
    "The HMRC authorisation window is already open. Complete it or close it before " +
            "connecting again. If you cannot see it, it may be behind another window."

/**
 * The status bar message for as long as an authorisation is in progress.
 * Worded to stay true from the click until the window closes, so it is
 * neither "opening" nor "open".
 */
internal const val AUTHENTICATING_STATUS_TEXT =
    "Authenticating: waiting for you to sign in to HMRC in the authorisation window"

/**
 * Which controls of the pane are shown and enabled. A pure description, so
 * it can be unit tested without the JavaFX toolkit.
 */
internal data class ConnectPaneState(
    val showConnect:           Boolean,
    val connectEnabled:        Boolean,
    val showDisconnect:        Boolean,
    val disconnectEnabled:     Boolean,
    val showAuthorisingNotice: Boolean,
)

/**
 * [connected] is whether there is a usable HMRC token. [authorising] is
 * whether an authorisation window is open. Nothing may be pressed while a
 * window is open; the notice and the Show authorisation window button take
 * the place of the actions.
 */
internal fun connectPaneState(connected: Boolean, authorising: Boolean) = ConnectPaneState(
    showConnect           = !connected,
    connectEnabled        = !authorising,
    showDisconnect        = connected,
    disconnectEnabled     = !authorising,
    showAuthorisingNotice = authorising,
)

/**
 * The status bar indicator for a freshly built pane. A pane built while a
 * window is open must show Authenticating, not Disconnected.
 */
internal fun connectionStatusFor(connected: Boolean, authorising: Boolean): MtdConnectionStatus = when {
    connected   -> MtdConnectionStatus.Connected
    authorising -> MtdConnectionStatus.Authenticating
    else        -> MtdConnectionStatus.Disconnected
}

class ConnectPane(
    private val scope:              CoroutineScope,
    private val userId:             Int,
    private val settingsRepository: SettingsRepository,
    private val onStatusChange:     (String) -> Unit,
    private val onMtdStatusChange:  (MtdConnectionStatus) -> Unit,
) {

    private val connectBtn = Button("Connect to HMRC (Authorise via OAuth 2.0)").apply {
        styleClass.add("primary-action-button")
        padding = Insets(10.0, 20.0, 10.0, 20.0)
        setOnAction { handleConnect() }
    }

    private val disconnectBtn = Button("Disconnect").apply {
        styleClass.add("danger-action-button")
        setOnAction { handleDisconnect() }
    }

    private val authorisingNotice = hintLabel(AUTHORISATION_OPEN_HINT)

    private val showWindowBtn = Button("Show authorisation window").apply {
        styleClass.add("secondary-action-button")
        setOnAction { AuthorisationWindow.bringToFront() }
    }

    // Held in a field because the property keeps only a weak reference to
    // it (see below), so it must be reachable for as long as the pane is.
    private val authorisingListener = ChangeListener<Boolean> { _, _, _ -> applyState() }

    val root: VBox = buildUI()

    init {
        // The pane is rebuilt on every visit but the property lives for the
        // whole process, so a strong listener would pile up. A weak one goes
        // away with the pane.
        AuthorisationWindow.activeProperty.addListener(WeakChangeListener(authorisingListener))

        applyState()

        // The left-hand status bar message for a pane built during an
        // authorisation is chosen by MainWindow.navigateTo (see
        // navigationStatusText), which writes after this constructor and
        // would overwrite anything written here.
        onMtdStatusChange(
            connectionStatusFor(TokenStore.isConnected(), AuthorisationWindow.isActive())
        )
    }

    private fun buildUI(): VBox =
        VBox(16.0).apply {
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
                authorisingNotice,
                showWindowBtn,
            )
        }

    private fun setShown(node: Node, shown: Boolean) {
        node.isVisible = shown
        node.isManaged = shown
    }

    /**
     * Brings the controls into line with the connection and with whether an
     * authorisation window is open. Both are read afresh each time, so this
     * is right whichever pane started the authorisation and however long ago.
     * JavaFX thread only.
     */
    private fun applyState() {
        val s = connectPaneState(TokenStore.isConnected(), AuthorisationWindow.isActive())

        setShown(connectBtn, s.showConnect)
        connectBtn.isDisable = !s.connectEnabled

        setShown(disconnectBtn, s.showDisconnect)
        disconnectBtn.isDisable = !s.disconnectEnabled

        setShown(authorisingNotice, s.showAuthorisingNotice)
        setShown(showWindowBtn, s.showAuthorisingNotice)
    }

    private fun handleConnect() {
        // Taken here, on the JavaFX thread, before anything is launched, so
        // that a second click or a double click cannot get past.
        if (!AuthorisationWindow.tryBegin()) {
            AuthorisationWindow.bringToFront()
            onStatusChange(AUTHENTICATING_STATUS_TEXT)
            return
        }

        applyState()
        onMtdStatusChange(MtdConnectionStatus.Authenticating)
        onStatusChange(AUTHENTICATING_STATUS_TEXT)

        scope.launch {
            try {
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
                    } else {
                        onMtdStatusChange(MtdConnectionStatus.Error)
                        onStatusChange("HMRC authorisation failed or was cancelled")
                    }
                    applyState()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "HMRC connection failed unexpectedly" }
                Platform.runLater {
                    onMtdStatusChange(MtdConnectionStatus.Error)
                    onStatusChange("HMRC connection failed – see the log")
                }
            } finally {
                // Whatever happened, the next Connect must be possible. The
                // result handler above is queued before this runs, so a
                // pane waiting on the gate sees the new token first.
                AuthorisationWindow.end()
            }
        }
    }

    private fun handleDisconnect() {
        TokenStore.clear(userId)
        onMtdStatusChange(MtdConnectionStatus.Disconnected)
        onStatusChange("Disconnected from HMRC")
        applyState()
    }
}
