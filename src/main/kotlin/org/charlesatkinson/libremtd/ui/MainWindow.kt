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
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.stage.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.charlesatkinson.libremtd.database.SettingsRepository
import org.charlesatkinson.libremtd.database.User
import org.charlesatkinson.libremtd.network.ClientContext
import org.charlesatkinson.libremtd.network.HmrcApiClient
import org.charlesatkinson.libremtd.security.FraudPreventionHeaders
import org.charlesatkinson.libremtd.security.OAuth2Handler
import org.charlesatkinson.libremtd.security.TokenStore
import org.charlesatkinson.libremtd.utils.Config
import org.charlesatkinson.libremtd.ui.components.ThemeManager
import org.charlesatkinson.libremtd.ui.components.UiPreferences
import org.charlesatkinson.libremtd.ui.components.UiTheme
import org.charlesatkinson.libremtd.ui.components.wrappingLabel
import javafx.scene.control.MenuItem

private val logger = KotlinLogging.logger {}

enum class MtdConnectionStatus {
    Disconnected, Authenticating, Connected, Error
}

/**
 * [label] is the full descriptive text — used for the status bar ("Viewing: ...")
 * and anywhere else a fully-qualified destination name is needed.
 * [navLabel] is the short text shown on the sidebar button itself, where the
 * enclosing section header (INCOME / EXPENSES / PROPERTIES / etc.) already
 * supplies context, so it defaults to [label] and is only overridden where a
 * shorter form is needed to fit the sidebar width.
 */
enum class NavDestination(val label: String, val navLabel: String = label) {
    AboutLibreMTD("About LibreMTD"),
    Dashboard("Dashboard"),
    DividendIncome("Income (dividends)", navLabel = "Dividends"),
    ExpensesPropertyUk("Expenses (property, UK)", navLabel = "UK property"),
    ExpensesPropertyForeign("Expenses (property, foreign)", navLabel = "Foreign property"),
    ExportSpreadsheet("Export Spreadsheet"),
    Help("Help"),
    HmrcConnect("Connect"),
    HmrcLinks("HMRC Links"),
    ImportSpreadsheet("Import Spreadsheet"),
    IncomePropertyUk("Income (property, UK)", navLabel = "UK property"),
    IncomePropertyForeign("Income (property, foreign)", navLabel = "Foreign property"),
    Properties("Properties", navLabel = "Manage"),
    SavingsIncome("Income (savings)", navLabel = "Savings"),
    Settings("Settings"),
    Submissions("Submissions"),
    TaxSummary("Tax Summary"),
}

// Destinations whose panes must not be cached because they depend on
// connection / settings state that may change between visits.
private val UNCACHED_DESTINATIONS = setOf(NavDestination.HmrcConnect)

class MainWindow(
    private val scope:              CoroutineScope,
    private val stage:              Stage,
    private val user:               User,
    private val settingsRepository: SettingsRepository = SettingsRepository(),
    private val initialMtdStatus:   MtdConnectionStatus = MtdConnectionStatus.Disconnected,
) {
    private val prefs = UiPreferences(user.id)
    val root: BorderPane = BorderPane().apply {
        styleClass.add("main-root")
    }

    private var currentNav: NavDestination = NavDestination.Dashboard
    private var lastUpdateTime: Long = 0L

    private val statusLabel    = wrappingLabel("Ready")
    private val mtdStatusLabel = wrappingLabel("● Disconnected").apply {
        styleClass.add("mtd-status-disconnected")
    }

    private val contentArea = BorderPane().apply {
        padding = Insets(20.0)
    }

    private val fraudPrevention = FraudPreventionHeaders()
    private var apiClient: HmrcApiClient? = null

    // Pane cache: built once, reused on subsequent visits.
    // Destinations in UNCACHED_DESTINATIONS are excluded and rebuilt every time.
    private val paneCache = mutableMapOf<NavDestination, javafx.scene.Node>()

    private suspend fun ensureApiClient(): HmrcApiClient? {
        apiClient?.let { return it }

        val settings = withContext(Dispatchers.IO) {
            settingsRepository.load(user.id)
        }

        if (settings == null || settings.clientId.isBlank()) {
            setStatus("Enter Client ID and Secret in Settings first")
            return null
        }

        return HmrcApiClient(
            libreMtdUserId = user.id,
            isSandbox      = Config.hmrcSandbox,
            oauth2Handler  = OAuth2Handler(
                clientId     = settings.clientId,
                clientSecret = settings.clientSecret,
                prefs        = prefs,
                isSandbox    = Config.hmrcSandbox,
            ),
            fraudHeaders   = fraudPrevention,
        ).also { apiClient = it }
    }

    init {
        buildUI()
    }

    private fun buildUI() {
        root.top    = buildMenuBar()
        root.left   = buildSidebar()
        root.bottom = buildStatusBar()

        val scroll = ScrollPane(contentArea).apply {
            isFitToWidth  = true
            isFitToHeight = false
            hbarPolicy    = ScrollPane.ScrollBarPolicy.NEVER
            vbarPolicy    = ScrollPane.ScrollBarPolicy.AS_NEEDED
            styleClass.add("edge-to-edge-scroll")
        }

        root.center = scroll

        navigateTo(NavDestination.Dashboard)
    }

    private fun buildMenuBar(): MenuBar {
        val fileMenu = Menu("File").apply {
            items.addAll(
                MenuItem("Export spreadsheet").apply {
                    setOnAction { navigateTo(NavDestination.ExportSpreadsheet) }
                },
                MenuItem("Import spreadsheet").apply {
                    setOnAction { navigateTo(NavDestination.ImportSpreadsheet) }
                },
                SeparatorMenuItem(),
                MenuItem("Log out").apply {
                    setOnAction { handleLogOut() }
                },
            )
        }

        val settingsMenu = Menu("Settings").apply {
            val themeMenu = Menu("Theme").apply {
                val toggleGroup = ToggleGroup()
                UiTheme.entries.forEach { theme ->
                    items.add(RadioMenuItem(theme.label).apply {
                        this.toggleGroup = toggleGroup
                        isSelected = (prefs.theme == theme)
                        setOnAction {
                            val scene = parentMenu?.parentPopup?.ownerNode?.scene
                                ?: parentMenu?.parentMenu?.parentPopup?.ownerNode?.scene
                            if (scene != null) ThemeManager.switchTo(scene, theme, prefs)
                        }
                    })
                }
                setOnShown { applyMenuItemColors() }
            }

            items.add(themeMenu)
        }

        val helpMenu = Menu("Help").apply {
            items.addAll(
                MenuItem("LibreMTD help").apply {
                    setOnAction { navigateTo(NavDestination.Help) }
                },
                MenuItem("HMRC links").apply {
                    setOnAction { navigateTo(NavDestination.HmrcLinks) }
                },
            )
        }

        val menuBar = MenuBar().apply {
            menus.addAll(fileMenu, settingsMenu, helpMenu)
        }

        listOf(fileMenu, settingsMenu, helpMenu).forEach { menu ->
            menu.setOnShown { applyMenuItemColors() }
        }

        return menuBar
    }

    private fun buildSidebar(): VBox {
        val sidebar = VBox(4.0).apply {
            padding   = Insets(12.0)
            prefWidth = 190.0
            styleClass.add("menu-bar")
        }

        val sectionLabel: (String) -> Label = { text ->
            wrappingLabel(text).apply {
                styleClass.setAll("menu")
                style   = "-fx-font-size: 10px; -fx-font-weight: bold;"
                padding = Insets(8.0, 0.0, 2.0, 4.0)
            }
        }

        fun navButton(dest: NavDestination) = Button(dest.navLabel).apply {
            maxWidth           = Double.MAX_VALUE
            isFocusTraversable = false
            styleClass.setAll("menu")
            setOnAction { navigateTo(dest) }
        }

        sidebar.children.addAll(
            wrappingLabel(user.username).apply {
                styleClass.add("menu")
                style   = "-fx-font-weight: bold; -fx-font-size: 13px;"
                padding = Insets(0.0, 0.0, 8.0, 4.0)
            },
            Separator(),
            sectionLabel("OVERVIEW"),
            navButton(NavDestination.Dashboard),
            navButton(NavDestination.TaxSummary),
            Separator(),
            sectionLabel("INCOME"),
            navButton(NavDestination.DividendIncome),
            navButton(NavDestination.IncomePropertyUk),
            navButton(NavDestination.IncomePropertyForeign),
            navButton(NavDestination.SavingsIncome),
            sectionLabel("EXPENSES"),
            navButton(NavDestination.ExpensesPropertyUk),
            navButton(NavDestination.ExpensesPropertyForeign),
            sectionLabel("PROPERTIES"),
            navButton(NavDestination.Properties),
            Separator(),
            sectionLabel("HMRC"),
            navButton(NavDestination.Settings),
            navButton(NavDestination.HmrcConnect),
            navButton(NavDestination.Submissions),
        )

        return sidebar
    }

    private fun buildStatusBar(): HBox {
        updateMtdStatus(initialMtdStatus)

        return HBox(12.0).apply {
            padding   = Insets(5.0, 10.0, 5.0, 10.0)
            styleClass.add("status-bar")
            alignment = Pos.CENTER_LEFT
            children.addAll(
                statusLabel,
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                mtdStatusLabel,
            )
        }
    }

    private fun navigateTo(dest: NavDestination) {
        currentNav = dest
        try {
            val pane = if (dest in UNCACHED_DESTINATIONS) {
                buildFreshPane(dest)
            } else {
                paneCache.getOrPut(dest) { buildFreshPane(dest) }
            }
            contentArea.center = pane
            setStatus("Viewing: ${dest.label}")
        } catch (e: Exception) {
            logger.error(e) { "Failed to build pane for $dest" }
            setStatus("Error opening ${dest.label} — see log")
        }
    }

    private fun buildFreshPane(dest: NavDestination): javafx.scene.Node = when (dest) {
        NavDestination.AboutLibreMTD          -> HelpPane(
            userId         = user.id,
        )
        NavDestination.Dashboard              -> DashboardPane().root
        NavDestination.DividendIncome         -> DividendIncomePane(
            scope          = scope,
            userId         = user.id,
            onStatusChange = { msg -> setStatus(msg) },
        ).root
        NavDestination.ExpensesPropertyUk     -> ExpensesPropertyUkPane(
            scope          = scope,
            userId         = user.id,
            onStatusChange = { msg -> setStatus(msg) },
        ).root
        NavDestination.ExpensesPropertyForeign -> ExpensesPropertyForeignPane(
            scope          = scope,
            userId         = user.id,
            onStatusChange = { msg -> setStatus(msg) },
        ).root
        NavDestination.ExportSpreadsheet      -> ExportSpreadsheetPane(
            scope          = scope,
            userId         = user.id,
            onStatusChange = { msg -> setStatus(msg) },
        ).root
        NavDestination.Help                   -> HelpPane(userId = user.id)
        NavDestination.HmrcConnect            -> ConnectPane(
            scope              = scope,
            userId             = user.id,
            settingsRepository = settingsRepository,
            onStatusChange     = { msg -> setStatus(msg) },
            onMtdStatusChange  = { status ->
                updateMtdStatus(status)
                if (status == MtdConnectionStatus.Connected) {
                    notifyConnected()
                }
            },
        ).root
        NavDestination.HmrcLinks              -> HelpHmrcPane()
        NavDestination.ImportSpreadsheet      -> ImportSpreadsheetPane(
            scope          = scope,
            userId         = user.id,
            onStatusChange = { msg -> setStatus(msg) },
        ).root
        NavDestination.IncomePropertyUk       -> IncomePropertyUkPane(
            scope          = scope,
            userId         = user.id,
            onStatusChange = { msg -> setStatus(msg) },
        ).root
        NavDestination.IncomePropertyForeign  -> IncomePropertyForeignPane(
            scope          = scope,
            userId         = user.id,
            onStatusChange = { msg -> setStatus(msg) },
        ).root
        NavDestination.Properties             -> PropertiesPane(
            scope              = scope,
            userId             = user.id,
            settingsRepository = settingsRepository,
            onStatusChange     = { msg -> setStatus(msg) },
        ).root
        NavDestination.SavingsIncome          -> SavingsIncomePane(
            scope          = scope,
            userId         = user.id,
            onStatusChange = { msg -> setStatus(msg) },
        ).root
        NavDestination.Settings               -> SettingsPane(
            scope              = scope,
            userId             = user.id,
            settingsRepository = settingsRepository,
            onStatusChange     = { msg -> setStatus(msg) },
            onSettingsSaved    = {
                apiClient = null
                paneCache.remove(NavDestination.Submissions)
                paneCache.remove(NavDestination.TaxSummary)
            },
        ).root
        NavDestination.Submissions            -> SubmissionsPane(
            scope              = scope,
            userId             = user.id,
            settingsRepository = settingsRepository,
            getApiClient       = { ensureApiClient() },
            getContext         = { currentClientContext() },
            onStatusChange     = { msg -> setStatus(msg) },
        ).root
        NavDestination.TaxSummary             -> TaxSummaryPane(
            scope              = scope,
            userId             = user.id,
            settingsRepository = settingsRepository,
            getApiClient       = { ensureApiClient() },
            getContext         = { currentClientContext() },
            onStatusChange     = { msg -> setStatus(msg) },
        ).root
    }

    /**
     * Called when ConnectPane signals a successful HMRC connection.
     * Tells any already-cached panes that depend on connection state to refresh.
     */
    private fun notifyConnected() {
        (paneCache[NavDestination.Submissions] as? SubmissionsPane.RefreshableRoot)
            ?.refreshablePane
            ?.refresh()
    }

    fun setStatus(message: String) {
        Platform.runLater { statusLabel.text = message }
    }

    fun updateMtdStatus(status: MtdConnectionStatus) {
        Platform.runLater {
            mtdStatusLabel.text = when (status) {
                MtdConnectionStatus.Disconnected   -> "● Disconnected"
                MtdConnectionStatus.Authenticating -> "◌ Authenticating…"
                MtdConnectionStatus.Connected      -> "● Connected"
                MtdConnectionStatus.Error          -> "● Error"
            }
            mtdStatusLabel.styleClass.removeIf { it.startsWith("mtd-status-") }
            mtdStatusLabel.styleClass.add(when (status) {
                MtdConnectionStatus.Connected      -> "mtd-status-connected"
                MtdConnectionStatus.Error          -> "mtd-status-error"
                MtdConnectionStatus.Authenticating -> "mtd-status-authenticating"
                MtdConnectionStatus.Disconnected   -> "mtd-status-disconnected"
            })
        }
    }

    private fun handleLogOut() {
        TokenStore.clearMemory()
        paneCache.clear()
        setStatus("Logged out")
    }

    private fun setupWindowSizeListeners() {
        val updateHeader = {
            val now = System.currentTimeMillis()
            if (now - lastUpdateTime > 300) {
                lastUpdateTime = now
                val ctx = currentClientContext()
                fraudPrevention.updateWindowSizeHeader(ctx.windowWidth, ctx.windowHeight)
            }
        }
        stage.widthProperty().addListener  { _, _, _ -> updateHeader() }
        stage.heightProperty().addListener { _, _, _ -> updateHeader() }
        updateHeader()
    }

    fun currentClientContext(): ClientContext =
        ClientContext(windowWidth = stage.width.toInt(), windowHeight = stage.height.toInt())

    private fun currentThemeMenuItemColor(): javafx.scene.paint.Color = when (prefs.theme) {
        UiTheme.GREEN -> javafx.scene.paint.Color.web("#1a2e22")
        UiTheme.LIGHT -> javafx.scene.paint.Color.web("#212121")
        UiTheme.DARK  -> javafx.scene.paint.Color.web("#bbbbbb")
    }

    private fun applyMenuItemColors() {
        val color = currentThemeMenuItemColor()
        javafx.stage.Window.getWindows()
            .filterIsInstance<javafx.stage.PopupWindow>()
            .forEach { popup ->
                popup.scene?.root?.lookupAll(".menu-item")?.forEach { node ->
                    node.lookupAll(".label").forEach { labelNode ->
                        if (labelNode is javafx.scene.control.Labeled) {
                            labelNode.textFill = color
                        }
                    }
                }
            }
    }
}
