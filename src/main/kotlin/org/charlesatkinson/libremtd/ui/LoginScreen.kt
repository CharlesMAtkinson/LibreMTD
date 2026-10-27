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

import javafx.beans.value.ChangeListener
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
import org.charlesatkinson.libremtd.utils.AppPaths
import org.charlesatkinson.libremtd.utils.SessionInstanceLock
import org.charlesatkinson.libremtd.utils.UserInstanceLock
import org.charlesatkinson.libremtd.utils.UserLogging
import org.charlesatkinson.libremtd.utils.enforceLogFilePermissions
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}
private val settingsRepository = SettingsRepository()

/**
 * @param autoLogin When true (the default) and development mode is on, logs
 *                  in straight away. False after a logout, otherwise a
 *                  development-mode logout would log straight back in.
 */
class LoginScreen(
    private val primaryStage: Stage,
    private val scope: CoroutineScope,
    autoLogin: Boolean = true,
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
        // The development password is deliberately not logged.
        logger.info { "devMode = $devMode" }

        buildUI()
        setupEventHandlers()

        if (devMode) {
            usernameField.text = devUsername
            passwordField.text = devPassword
            if (autoLogin) {
                logger.info { "Development mode enabled - attempting auto-login as $devUsername" }
                handleLogin()
            }
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
            isWrapText = true
            maxWidth = 340.0
            // Without this, the VBox can size the label to the one-line height
            // it has at construction time, so a long message which wraps onto
            // a second line is clipped and ends in "…". USE_PREF_SIZE makes the
            // layout use the label's own wrapped height. (Same fix as hintLabel
            // in ReadOnlyLabels.kt.)
            minHeight = Region.USE_PREF_SIZE
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
            // The instance lock is taken straight after authentication, so
            // the lock check cannot be used to find out which user names
            // exist. See UserInstanceLock for how stale locks are handled.
            val (user, lockOutcome) = withContext(Dispatchers.IO) {
                val authenticated = UserRepository.authenticate(username, password)
                authenticated to authenticated?.let { SessionInstanceLock.acquire(AppPaths.locksDir, it.id) }
            }

            withContext(Dispatchers.JavaFx) {
                loginButton.isDisable = false

                when {
                    user == null -> {
                        statusLabel.text = "Invalid username or password"
                        passwordField.clear()
                    }

                    lockOutcome is UserInstanceLock.Outcome.Acquired -> completeLogin(user)

                    lockOutcome is UserInstanceLock.Outcome.HeldElsewhere -> {
                        logger.warn { "Login refused for ${user.username}: already logged in in ${lockOutcome.holder}" }
                        statusLabel.text = "${user.username} is already logged in in another LibreMTD window " +
                                "(${lockOutcome.holder}).  Close that window first."
                        passwordField.clear()
                    }

                    lockOutcome is UserInstanceLock.Outcome.Failed -> {
                        logger.error { "Login refused for ${user.username}: ${lockOutcome.message}" }
                        statusLabel.text = lockOutcome.message
                        passwordField.clear()
                    }
                }
            }
        }
    }

    /** Called on the JavaFX thread once the user is authenticated and holds the instance lock. */
    private fun completeLogin(user: User) {
        // From here until logout, log lines go to <user name>.log
        UserLogging.setCurrentUser(user.username)
        logger.info { "User ${user.username} logged in successfully" }
        // The user's log file now exists; do not wait for the periodic fix-up.
        enforceLogFilePermissions(AppPaths.logDir)

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

        try {
            openMainWindow(user)
        } catch (e: Exception) {
            logger.error(e) { "Failed to open the main window for ${user.username}" }
            SessionInstanceLock.release()
            UserLogging.clearCurrentUser()
            TokenStore.clearMemory()
            statusLabel.text = "Could not open the main window – see the log"
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

    private fun createMainWindow(
        user: User,
        initialMtdStatus: MtdConnectionStatus,
        onLogOut: () -> Unit,
    ): MainWindow {
        return MainWindow(
            scope = scope,
            stage = primaryStage,
            user = user,
            settingsRepository = settingsRepository,
            initialMtdStatus = initialMtdStatus,
            onLogOut = onLogOut,
        )
    }

    private fun openMainWindow(user: User) {
        val prefs = UiPreferences(user.id)
        val initialMtdStatus = if (!TokenStore.isExpired() && TokenStore.getAccessToken() != null)
            MtdConnectionStatus.Connected
        else
            MtdConnectionStatus.Disconnected

        // Assigned below, once the geometry listeners exist; the log out
        // callback only runs later, long after that.
        var detachGeometrySaving: () -> Unit = {}

        val mainWindow = createMainWindow(user, initialMtdStatus) {
            returnToLoginScreen(detachGeometrySaving)
        }

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

        detachGeometrySaving = attachGeometrySaving(prefs)
    }

    /**
     * Saves the window geometry to [prefs] whenever the window is moved or
     * resized. Returns a function which stops that again: the stage outlives
     * a login, so without it every login would add another set of listeners,
     * and after a logout they would keep writing the previous user's
     * preferences.
     */
    private fun attachGeometrySaving(prefs: UiPreferences): () -> Unit {
        val xListener      = ChangeListener<Number> { _, _, v -> prefs.windowX      = v.toDouble() }
        val yListener      = ChangeListener<Number> { _, _, v -> prefs.windowY      = v.toDouble() }
        val widthListener  = ChangeListener<Number> { _, _, v -> prefs.windowWidth  = v.toDouble() }
        val heightListener = ChangeListener<Number> { _, _, v -> prefs.windowHeight = v.toDouble() }

        primaryStage.xProperty().addListener(xListener)
        primaryStage.yProperty().addListener(yListener)
        primaryStage.widthProperty().addListener(widthListener)
        primaryStage.heightProperty().addListener(heightListener)

        return {
            primaryStage.xProperty().removeListener(xListener)
            primaryStage.yProperty().removeListener(yListener)
            primaryStage.widthProperty().removeListener(widthListener)
            primaryStage.heightProperty().removeListener(heightListener)
        }
    }

    /**
     * Log out: puts a fresh login screen back in the window and releases
     * everything the session held. The token and pane caches were already
     * cleared by MainWindow before it called this.
     */
    private fun returnToLoginScreen(detachGeometrySaving: () -> Unit) {
        // Logged before the user is cleared, so it lands in that user's log.
        logger.info { "User logged out" }

        detachGeometrySaving()
        HelpWindow.close()
        SessionInstanceLock.release()
        UserLogging.clearCurrentUser()

        val next = LoginScreen(primaryStage, scope, autoLogin = false)
        val scene = primaryStage.scene
        scene.root = next.root
        ThemeManager.applyLoginTheme(scene)
        primaryStage.title = "LibreMTD - Making Tax Digital"

        logger.info { "Returned to the login screen" }
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
