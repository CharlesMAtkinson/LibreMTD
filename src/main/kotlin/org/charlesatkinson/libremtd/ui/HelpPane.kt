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
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.layout.BorderPane
import javafx.scene.layout.VBox
import mu.KotlinLogging
import org.charlesatkinson.libremtd.ui.components.hintLabel
import org.charlesatkinson.libremtd.ui.components.UiPreferences
import org.charlesatkinson.libremtd.ui.components.normalizeText


private val logger = KotlinLogging.logger {}

class HelpPane(
    private val userId: Int,
    initialTopic: Topic = Topic.Introduction
) : BorderPane() {
    private val prefs = UiPreferences(userId)

    enum class Topic(val label: String) {
        Introduction("Introduction"),
        // OVERVIEW
        Dashboard("Dashboard"),
        TaxSummary("Tax Summary"),
        // SUBMIT DATA
        DividendIncome("Income (dividends)"),
        PropertyIncome("Income (property)"),
        SavingsIncome("Income (savings)"),
        Expenses("Expenses"),
        Allowances("Allowances"),
        Submissions("Submissions"),
        // ACCOUNT
        HmrcConnect("HMRC Connect"),
        Properties("Properties"),
        Settings("Settings"),
        // FILE
        ExportImport("Export & Import"),
        // OTHER
        About("About LibreMTD"),
    }

    /** A sidebar entry is either a selectable topic or a non-selectable section label. */
    private sealed class SidebarItem {
        data class TopicItem(val topic: Topic) : SidebarItem()
        data class SectionLabel(val text: String) : SidebarItem()
    }

    init {
        val sidebarItems: List<SidebarItem> = listOf(
            SidebarItem.TopicItem(Topic.Introduction),
            SidebarItem.SectionLabel("OVERVIEW"),
            SidebarItem.TopicItem(Topic.Dashboard),
            SidebarItem.TopicItem(Topic.TaxSummary),
            SidebarItem.SectionLabel("SUBMIT DATA"),
            SidebarItem.TopicItem(Topic.DividendIncome),
            SidebarItem.TopicItem(Topic.PropertyIncome),
            SidebarItem.TopicItem(Topic.SavingsIncome),
            SidebarItem.TopicItem(Topic.Expenses),
            SidebarItem.TopicItem(Topic.Allowances),
            SidebarItem.TopicItem(Topic.Submissions),
            SidebarItem.SectionLabel("ACCOUNT"),
            SidebarItem.TopicItem(Topic.HmrcConnect),
            SidebarItem.TopicItem(Topic.Properties),
            SidebarItem.TopicItem(Topic.Settings),
            SidebarItem.SectionLabel("FILE"),
            SidebarItem.TopicItem(Topic.ExportImport),
            SidebarItem.SectionLabel(""),   // spacer before About
            SidebarItem.TopicItem(Topic.About),
        )

        val topicList = ListView<SidebarItem>().apply {
            items.addAll(sidebarItems)
            prefWidth = 200.0
            minWidth  = 160.0
            cellFactory = javafx.util.Callback {
                object : ListCell<SidebarItem>() {
                    override fun updateItem(item: SidebarItem?, empty: Boolean) {
                        super.updateItem(item, empty)
                        text  = null
                        style = ""
                        graphic = null
                        isDisable = false

                        if (empty || item == null) return

                        when (item) {
                            is SidebarItem.TopicItem -> {
                                text = item.topic.label
                            }
                            is SidebarItem.SectionLabel -> {
                                text      = item.text
                                style     = "-fx-font-size: 10px; -fx-font-weight: bold; " +
                                        "-fx-text-fill: #888888; -fx-padding: 8 0 2 4;"
                                isDisable = true   // not selectable
                            }
                        }
                    }
                }
            }
        }

        val contentArea = ScrollPane().apply {
            isFitToWidth = true
            isFitToHeight = true
            minHeight     = 0.0
            padding = Insets(16.0)
        }

        fun showTopic(topic: Topic) {
            contentArea.content = when (topic) {
                Topic.Introduction  -> buildIntroductionPane()
                Topic.Dashboard     -> buildPlaceholderPane("Dashboard",
                    "The Dashboard gives an at-a-glance view of your estimated tax liability, " +
                            "next submission deadline, and how many quarterly updates you have submitted.")
                Topic.TaxSummary    -> buildPlaceholderPane("Tax Summary",
                    "Tax Summary retrieves HMRC's latest calculation of your Income Tax and " +
                            "National Insurance contributions for the selected tax year.")
                Topic.DividendIncome -> buildPlaceholderPane("Income (dividends)",normalizeText(
                    """
                         Record dividend income for the tax year. Dividends are reported to HMRC annually.
                         {NL}{NL}
                         The pane has three sections. UK dividends from companies and funds covers cash
                         dividends from UK companies and dividends from UK unit trusts and OEICs (shown on 
                         fund platform tax certificates). UK dividends — special types covers stock dividends 
                         (shares received instead of cash), redeemable shares, bonus issues of securities, and 
                         close company loans written off — these arise in unusual circumstances. Foreign 
                         dividends covers dividends from overseas companies and dividend income received 
                         whilst abroad; each entry is per country and requires the taxable amount; other fields 
                         are optional.
                         {NL}{NL}
                         Dividends within your annual dividend allowance are not taxable but must still be 
                         reported. The allowance has been £500 since April 2024.
                      """
                    )
                )
                Topic.PropertyIncome -> buildPlaceholderPane("Income (property)",
                    "Record rental income for each property and period.  " +
                            "Furnished holiday lettings and standard residential lettings are both supported.")
                Topic.SavingsIncome  -> buildPlaceholderPane("Income (savings)",
                    "Record interest from bank accounts, building societies, and other savings sources.")
                Topic.Expenses      -> buildPlaceholderPane("Expenses",
                    "Record allowable property expenses such as repairs, insurance, and agent fees.  " +
                            "The consolidated expenses scheme is not supported so all expenses are assigned " +
                            "to a category.")
                Topic.Allowances    -> buildPlaceholderPane("Allowances",
                    "Claim the Property Income Allowance (up to £1,000) instead of actual expenses, " +
                            "or record other allowances applicable to your situation.")
                Topic.Submissions   -> buildPlaceholderPane("Submissions",
                    "Submit quarterly updates, End of Period Statements (EOPS), and the Final " +
                            "Declaration to HMRC via the MTD API.")
                Topic.HmrcConnect   -> buildPlaceholderPane("HMRC Connect",
                    "Authorise LibreMTD to communicate with HMRC using OAuth 2.0. " +
                            "Your user name and password is never stored. " +
                            "An access token is valid for four hours. For convenience it is kept in the database. " +
                            "To remove a valid access token from the database, click the Disconnect button.")
                Topic.Properties    -> buildPlaceholderPane("Properties",
                    "Add and manage the rental properties you report under MTD ITSA.")
                Topic.Settings      -> buildPlaceholderPane("Settings",
                    "Configure your HMRC Client ID, Client Secret, and National Insurance number (NINO).")
                Topic.ExportImport  -> buildExportImportPane()
                Topic.About         -> buildAboutPane()
            }
            prefs.lastHelpTopic = topic.name
        }

        // Wire selection — skip section labels (they are disabled but guard anyway)
        topicList.selectionModel.selectedItemProperty().addListener { _, _, item ->
            if (item is SidebarItem.TopicItem) showTopic(item.topic)
        }

        left   = topicList
        center = contentArea

        // Fill the height the outer ScrollPane viewport offers.
        minHeight = 0.0
        maxHeight = Double.MAX_VALUE
        VBox.setVgrow(this, javafx.scene.layout.Priority.ALWAYS)

        // Resolve initial topic: prefer constructor arg, else last saved, else Introduction
        val startTopic = initialTopic.takeIf { it != Topic.Introduction }
            ?: prefs.lastHelpTopic
                ?.let { name -> Topic.entries.firstOrNull { it.name == name } }
            ?: Topic.Introduction

        val startIndex = sidebarItems.indexOfFirst {
            it is SidebarItem.TopicItem && it.topic == startTopic
        }.takeIf { it >= 0 } ?: 0

        topicList.selectionModel.select(startIndex)
    }

    // ── Topic content builders ────────────────────────────────────────────────

    private fun buildPlaceholderPane(title: String, description: String): Node =
        VBox(14.0).apply {
            padding = Insets(8.0)
            children.addAll(
                heading(title),
                body(description),
            )
        }

    private fun buildIntroductionPane(): Node =
        VBox(14.0).apply {
            padding = Insets(8.0)
            children.addAll(
                heading("LibreMTD — Introduction"),
                body(
                    "LibreMTD is free and open-source software (FOSS) for submitting " +
                            "quarterly updates and end-of-period statements to HMRC under " +
                            "Making Tax Digital for Income Tax Self Assessment (MTD ITSA)."
                ),
                subheading("What LibreMTD supports"),
                body("Property income — record rental income and allowable expenses for " +
                        "furnished and unfurnished UK residential lettings, including furnished " +
                        "holiday lettings.  " +
                        "The property income allowance and rent-a-room schemes are not supported."
                ),
                body("Dividend income — record dividends received from UK and overseas companies."),
                body("Savings income — record interest from UK bank and building society accounts."),
                body("Submissions — submit quarterly updates for 6 April to 5 April tax years " +
                        "and the final declaration directly to HMRC"),
                subheading("Copying user interface dynamic text"),
                body("Dynamic text including error messages may be copied by context clicking the " +
                        "text and choosing Copy"),
                subheading("Files"),
                body("Log — ~/.local/state/LibreMTD/log/app.log"),
                body("Database — ~/.local/share/LibreMTD/app.db"),
                subheading("Free and open source (FOSS)"),
                body("LibreMTD is distributed under the GNU General Public Licence v3 (GPL-3.0). " +
                        "You are free to use, study, modify and redistribute it. The source code is available at:"),
                hyperlink("https://github.com/charlesatkinson/libremtd"),
                subheading("Disclaimer"),
                body("LibreMTD is independent software. It is not affiliated with, endorsed by, " +
                        "or supported by HMRC. Tax rules change — always verify your figures and " +
                        "submission obligations against current guidance on gov.uk. " +
                        "LibreMTD does not provide tax advice."),
            )
        }

    private fun buildExportImportPane(): Node =
        VBox(14.0).apply {
            padding = Insets(8.0)
            children.addAll(
                heading("Export & Import"),
                subheading("Exporting to a spreadsheet"),
                body("Use File > Export Spreadsheet to export your income, expenses, and/or " +
                        "allowances for a selected period to an .xlsx file."),
                body("Click 'Export to .xlsx spreadsheet', then accept or modify the file name " +
                        "and choose a destination folder."),
                subheading("Editing the spreadsheet"),
                body("Open the exported .xlsx file in LibreOffice Calc, Microsoft Excel, or any " +
                        "compatible application. You can correct figures, add missing entries, or " +
                        "reorganise rows. Do not add or remove columns, and do not change the header " +
                        "row — the importer matches columns by name."),
                subheading("Importing from a spreadsheet"),
                body("Use File > Import Spreadsheet to import an .xlsx file back into LibreMTD. " +
                        "Importing replaces existing entries for the selected period and category. " +
                        "Export first if you want to keep a backup of the current data."),
                subheading("Typical workflow"),
                body(
                    "1. Enter data directly in LibreMTD during the quarter.\n" +
                            "2. At end of quarter, export to .xlsx to review figures in a spreadsheet.\n" +
                            "3. Correct any entries in the spreadsheet and save.\n" +
                            "4. Import the corrected sheet back into LibreMTD.\n" +
                            "5. Submit the quarterly update from the Submissions pane."
                ),
            )
        }

    private fun buildAboutPane(): Node {
        val props = loadBuildProperties()
        return VBox(14.0).apply {
            padding = Insets(8.0)
            children.addAll(
                heading("About LibreMTD"),
                infoRow("Version",  props.getProperty("version",   "development build")),
                infoRow("Built",    props.getProperty("buildDate", "unknown")),
                infoRow("Kotlin",   KotlinVersion.CURRENT.toString()),
                infoRow("JVM",      System.getProperty("java.version")),
                infoRow("OS",       "${System.getProperty("os.name")} ${System.getProperty("os.version")}"),
                Separator(),
                body("Source code and issue tracker:"),
                hyperlink("https://github.com/CharlesMAtkinson/LibreMTD"),
                Separator(),
                subheading("Licence"),
                body("LibreMTD is distributed under the GNU General Public Licence v3."),
                hyperlink("https://www.gnu.org/licenses/gpl-3.0.html"),
            )
        }
    }

    // ── Widget helpers ────────────────────────────────────────────────────────

    private fun heading(text: String)    = Label(text).apply { style = "-fx-font-size: 18px; -fx-font-weight: bold;" }
    private fun subheading(text: String) = Label(text).apply { style = "-fx-font-size: 13px; -fx-font-weight: bold;" }
    private fun body(text: String)       = hintLabel(text).apply {
        isWrapText = true
        maxWidth   = Double.MAX_VALUE
        prefWidth = 1.0
        style = "-fx-font-size: 12px;"
    }
    private fun infoRow(key: String, value: String) = Label("$key:  $value")

    private fun hyperlink(url: String) = Hyperlink(url).apply {
        setOnAction { openUrl(url) }
        tooltip = Tooltip(url)
    }

    private fun openUrl(url: String) {
        Thread {
            try {
                ProcessBuilder("xdg-open", url)
                    .redirectErrorStream(true)
                    .start()
            } catch (e: Exception) {
                logger.warn { "Could not open $url: ${e.message}" }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun loadBuildProperties(): java.util.Properties {
        val props = java.util.Properties()
        try { javaClass.getResourceAsStream("/build.properties")?.use { props.load(it) } }
        catch (e: Exception) { logger.warn { "Could not load build.properties: ${e.message}" } }
        return props
    }
}