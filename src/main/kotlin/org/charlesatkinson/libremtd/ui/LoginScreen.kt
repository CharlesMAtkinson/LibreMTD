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
import javafx.stage.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.charlesatkinson.libremtd.database.User
import org.charlesatkinson.libremtd.database.UserRepository
import org.charlesatkinson.libremtd.utils.Config
import org.charlesatkinson.libremtd.database.SettingsRepository
import org.charlesatkinson.libremtd.security.TokenStore
import org.charlesatkinson.libremtd.ui.components.ThemeManager
import org.charlesatkinson.libremtd.ui.components.UiPreferences
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}
private val settingsRepository = SettingsRepository()

class LoginScreen(
    private val primaryStage: Stage,
    private val scope: CoroutineScope
) {
    val root: VBox = VBox(20.0)

    private val usernameField = TextField()
    private val passwordField = PasswordField()
    private val loginButton = Button("Login")
    private val registerButton = Button("Register")
    private val statusLabel = Label()

    private val devMode = Config.devMode
    private val devUsername = Config.devUsername
    private val devPassword = Config.devPassword

    init {
        logger.info { "devMode = $devMode" }
        logger.info { "devUsername = $devUsername" }
        logger.info { "devPassword = $devPassword" }

        buildUI()
        setupEventHandlers()

        // Auto-login in dev mode
        if (devMode) {
            logger.info { "Development mode enabled - attempting auto-login" }
            usernameField.text = devUsername
            passwordField.text = devPassword
            handleLogin()
        }
    }

    private fun buildUI() {
        root.apply {
            alignment = Pos.CENTER
            padding = Insets(40.0)
            styleClass.add("login-root")
        }

        // Icon
        val iconStream = javaClass.getResourceAsStream("/icons/libremtd-128.png")
        if (iconStream != null) {
            val icon = javafx.scene.image.ImageView(
                javafx.scene.image.Image(iconStream)
            ).apply {
                fitWidth = 96.0
                fitHeight = 96.0
                isPreserveRatio = true
            }
            root.children.add(icon)
        }

        val title = Label("LibreMTD").apply {
            styleClass.add("login-title")
        }

        val subtitle = Label("Free and open source software for HMRC's Making Tax Digital").apply {
            styleClass.add("login-subtitle")
        }

        if (devMode) {
            val devLabel = Label("🔧 DEVELOPMENT MODE").apply {
                styleClass.add("login-dev-label")
            }
            root.children.add(devLabel)
        }

        val formBox = VBox(15.0).apply {
            maxWidth = 400.0
            padding = Insets(30.0)
            styleClass.add("login-form-box")
        }

        usernameField.apply {
            promptText = "Username"
            prefWidth = 340.0
        }

        passwordField.apply {
            promptText = "Password"
            prefWidth = 340.0
        }

        val buttonBox = HBox(10.0).apply {
            alignment = Pos.CENTER
        }

        loginButton.apply {
            prefWidth = 165.0
            styleClass.add("primary-action-button")
        }

        registerButton.apply {
            prefWidth = 165.0
            styleClass.add("login-register-button")
        }

        buttonBox.children.addAll(loginButton, registerButton)

        statusLabel.apply {
            styleClass.add("status-error")
        }

        formBox.children.addAll(
            Label("Sign In").apply {
                styleClass.add("login-form-title")
            },
            usernameField,
            passwordField,
            buttonBox,
            statusLabel
        )

        root.children.addAll(title, subtitle, formBox)
    }

    private fun setupEventHandlers() {
        loginButton.setOnAction {
            handleLogin()
        }

        registerButton.setOnAction {
            handleRegister()
        }

        // Enter key on password field triggers login
        passwordField.setOnAction {
            handleLogin()
        }
    }

    private fun handleLogin() {
        val username = usernameField.text.trim()
        val password = passwordField.text

        if (username.isEmpty() || password.isEmpty()) {
            statusLabel.text = "Please enter username and password"
            return
        }

        loginButton.isDisable = true
        statusLabel.text = "Logging in..."

        scope.launch {
            val user = withContext(Dispatchers.IO) {
                UserRepository.authenticate(username, password)
            }

            withContext(Dispatchers.JavaFx) {
                loginButton.isDisable = false

                if (user != null) {
                    logger.info { "User ${user.username} logged in successfully" }

                    // Reload tokens from database if they exist and haven't expired
                    user.hmrcAccessToken?.let { accessToken ->
                        user.hmrcRefreshToken?.let { refreshToken ->
                            val expiry = user.tokenExpiry
                            if (expiry != null && expiry.isAfter(LocalDateTime.now().plusMinutes(1))) {
                                TokenStore.restore(
                                    accessToken  = accessToken,
                                    refreshToken = refreshToken,
                                    expiresAt    = expiry,
                                )
                                logger.info { "Restored valid tokens from database for userId=${user.id}" }
                            } else {
                                logger.info { "Stored tokens expired — user will need to reconnect to HMRC" }
                            }
                        }
                    }

                    openMainWindow(user)

                } else {
                    statusLabel.text = "Invalid username or password"
                    passwordField.clear()
                }
            }
        }
    }

    private fun handleRegister() {
        val dialog = RegisterDialog(primaryStage)
        val result = dialog.showAndWait()

        result.ifPresent { (username, password, email) ->
            scope.launch {
                val user = withContext(Dispatchers.IO) {
                    UserRepository.createUser(username, password, email)
                }

                withContext(Dispatchers.JavaFx) {
                    if (user != null) {
                        statusLabel.style = "-fx-text-fill: #4CAF50;"
                        statusLabel.text = "Account created! Please login."
                        usernameField.text = username
                        passwordField.clear()
                    } else {
                        statusLabel.style = "-fx-text-fill: #d32f2f;"
                        statusLabel.text = "Username already exists"
                    }
                }
            }
        }
    }

    private fun createMainWindow(user: User, initialMtdStatus: MtdConnectionStatus): MainWindow {
        return MainWindow(
            scope = scope,
            stage = primaryStage,
            user = user,
            settingsRepository = settingsRepository,
            initialMtdStatus = initialMtdStatus,
        )
    }

    private fun openMainWindow(user: User) {
        val prefs = UiPreferences(user.id)
        val initialMtdStatus = if (!TokenStore.isExpired() && TokenStore.getAccessToken() != null)
            MtdConnectionStatus.Connected
        else
            MtdConnectionStatus.Disconnected
        val mainWindow = createMainWindow(user, initialMtdStatus)

        val existingScene = primaryStage.scene
        existingScene.root = mainWindow.root
        ThemeManager.apply(existingScene, prefs)

        primaryStage.apply {
            prefs.windowWidth?.let  { width  = it }
            prefs.windowHeight?.let { height = it }
            prefs.windowX?.let      { x      = it }
            prefs.windowY?.let      { y      = it }
            title = "LibreMTD - ${user.username}"
        }

        // Save geometry whenever the window is moved or resized
        primaryStage.xProperty().addListener      { _, _, v -> prefs.windowX      = v.toDouble() }
        primaryStage.yProperty().addListener      { _, _, v -> prefs.windowY      = v.toDouble() }
        primaryStage.widthProperty().addListener  { _, _, v -> prefs.windowWidth  = v.toDouble() }
        primaryStage.heightProperty().addListener { _, _, v -> prefs.windowHeight = v.toDouble() }
    }
}

class RegisterDialog(ownerStage: Stage) : Dialog<Triple<String, String, String>>() {
    private val usernameField = TextField()
    private val emailField = TextField()
    private val passwordField = PasswordField()
    private val confirmPasswordField = PasswordField()
    
    init {
        initOwner(ownerStage)

        title = "Register New Account"
        headerText = "Create a new LibreMTD account"
        
        val grid = GridPane().apply {
            hgap = 10.0
            vgap = 10.0
            padding = Insets(20.0)
        }
        
        grid.add(Label("Username:"), 0, 0)
        grid.add(usernameField, 1, 0)
        
        grid.add(Label("Email (optional):"), 0, 1)
        grid.add(emailField, 1, 1)
        
        grid.add(Label("Password:"), 0, 2)
        grid.add(passwordField, 1, 2)
        
        grid.add(Label("Confirm Password:"), 0, 3)
        grid.add(confirmPasswordField, 1, 3)
        
        dialogPane.content = grid
        
        val registerButtonType = ButtonType("Register", ButtonBar.ButtonData.OK_DONE)
        dialogPane.buttonTypes.addAll(registerButtonType, ButtonType.CANCEL)
        
        setResultConverter { buttonType ->
            if (buttonType == registerButtonType) {
                Triple(usernameField.text, passwordField.text, emailField.text)
            } else {
                null
            }
        }
        
        // Validation
        val registerButton = dialogPane.lookupButton(registerButtonType)
        registerButton.isDisable = true
        
        val validateInputs = {
            val username = usernameField.text.trim()
            val password = passwordField.text
            val confirmPassword = confirmPasswordField.text
            
            registerButton.isDisable = username.length < 3 || 
                password.length < 8 || 
                password != confirmPassword
        }
        
        usernameField.textProperty().addListener { _, _, _ -> validateInputs() }
        passwordField.textProperty().addListener { _, _, _ -> validateInputs() }
        confirmPasswordField.textProperty().addListener { _, _, _ -> validateInputs() }
    }
}
