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

package org.charlesatkinson.libremtd

import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.stage.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mu.KotlinLogging
import org.charlesatkinson.libremtd.database.Database
import org.charlesatkinson.libremtd.ui.LoginScreen
import org.charlesatkinson.libremtd.utils.AppPaths
import org.charlesatkinson.libremtd.utils.Config
import org.charlesatkinson.libremtd.utils.enforceLogFilePermissions
import org.charlesatkinson.libremtd.utils.scheduleLogFilePermissionEnforcement

private val logger = KotlinLogging.logger {}

class LibreMTD : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun start(primaryStage: Stage) {
        logger.info { "Starting LibreMTD version ${Config.VERSION}" }
        logger.info { "Log directory: ${AppPaths.logDir}" }
        logger.info { "Config path: ${AppPaths.configPath}" }
        logger.info { "Data directory: ${AppPaths.dataDir}" }
        logger.info { "Database file: ${AppPaths.dbPath}" }
        logger.info { "Device ID file: ${AppPaths.deviceIdFile}" }

        try {
            // Fix up log file permissions now, and keep fixing them
            // periodically in case the app is left running across a
            // daily log rollover. AppPaths.logDir must be (or be
            // convertible to) a java.nio.file.Path — adjust below if
            // it is actually a File or String.
            enforceLogFilePermissions(AppPaths.logDir)
            scheduleLogFilePermissionEnforcement(AppPaths.logDir)

            // Initialize database
            Database.init(AppPaths.dbPath)
            logger.info { "Database initialized successfully" }

            // Create login screen
            val loginScreen = LoginScreen(primaryStage, applicationScope)
            val scene = Scene(loginScreen.root, 900.0, 600.0)

            val cssPath = javaClass.getResource("/styles/application.css")?.toExternalForm()
                ?: throw IllegalStateException("CSS file not found: /styles/application.css")
            scene.stylesheets.add(cssPath)

            val greenCssPath = javaClass.getResource("/styles/green-theme.css")?.toExternalForm()
                ?: throw IllegalStateException("CSS file not found: /styles/green-theme.css")
            scene.stylesheets.add(greenCssPath)

            primaryStage.apply {
                title = "LibreMTD - Making Tax Digital"
                this.scene = scene
                minWidth = 800.0
                minHeight = 600.0

                // Load window icons — JavaFX picks the closest size for each context
                listOf(16, 32, 48, 128, 256).forEach { size ->
                    val path = "icons/libremtd-$size.png"
                    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(path)
                    if (stream != null) {
                        icons.add(Image(stream))
                    } else {
                        logger.warn { "Icon not found: $path" }
                    }
                }

                show()
            }

            logger.info { "Application started successfully" }

        } catch (e: Exception) {
            logger.error(e) { "Failed to start application" }
            throw e
        }
    }

    override fun stop() {
        logger.info { "Shutting down LibreMTD" }
        Database.close()
        super.stop()
    }
}

fun main() {
    Application.launch(LibreMTD::class.java)
}
