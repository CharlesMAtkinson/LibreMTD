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
import javafx.scene.control.*
import javafx.scene.layout.VBox
import javafx.scene.text.Text
import javafx.scene.text.TextFlow
import java.awt.Desktop
import java.net.URI
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class HelpHmrcPane : ScrollPane() {

    init {
        isFitToWidth = true
        padding = Insets(16.0)

        val sections = listOf(
            "Getting Started" to listOf(
                "MTD for Income Tax — overview" to
                        "https://www.gov.uk/guidance/use-making-tax-digital-for-income-tax",
                "Sign up for MTD for Income Tax" to
                        "https://www.gov.uk/guidance/sign-up-your-business-for-making-tax-digital-for-income-tax",
                "MTD for Income Tax — who must sign up" to
                        "https://www.gov.uk/guidance/check-if-youre-eligible-for-making-tax-digital-for-income-tax",
            ),
            "Property Income" to listOf(
                "Rental income — what to report" to
                        "https://www.gov.uk/guidance/income-tax-when-you-rent-out-a-property-working-out-your-rental-income",
                "Allowable expenses for landlords" to
                        "https://www.gov.uk/guidance/income-tax-when-you-rent-out-a-property-working-out-your-rental-income#allow-expense",
                "Furnished holiday lettings rules" to
                        "https://www.gov.uk/guidance/furnished-holiday-lettings",
            ),
            "Dividends" to listOf(
                "Tax on dividends and dividend allowance" to
                        "https://www.gov.uk/tax-on-dividends",
                "Self Assessment: foreign income" to
                        "https://www.gov.uk/tax-foreign-income",
                "Foreign Tax Credit Relief" to
                        "https://www.gov.uk/tax-foreign-income/taxed-twice",
                ),
            "Quarterly Updates & Submissions" to listOf(
                "MTD quarterly updates explained" to
                        "https://www.gov.uk/guidance/use-making-tax-digital-for-income-tax#quarterly-updates",
                "End of period statements" to
                        "https://www.gov.uk/guidance/use-making-tax-digital-for-income-tax#end-of-period-statements",
                "Final declaration" to
                        "https://www.gov.uk/guidance/use-making-tax-digital-for-income-tax#final-declaration",
            ),
            "HMRC Developer Hub (API)" to listOf(
                "MTD ITSA API documentation" to
                        "https://developer.service.hmrc.gov.uk/api-documentation/docs/api?filter=income-tax-mtd",
                "OAuth 2.0 authorisation" to
                        "https://developer.service.hmrc.gov.uk/api-documentation/docs/authorisation",
                "Fraud prevention headers" to
                        "https://developer.service.hmrc.gov.uk/guides/fraud-prevention/",
                "HMRC sandbox environment" to
                        "https://developer.service.hmrc.gov.uk/api-documentation/docs/testing",
            ),
        )

        val content = VBox(20.0).apply {
            padding = Insets(8.0)
        }

        content.children.add(Label("HMRC MTD Documentation").apply {
            style = "-fx-font-size: 18px; -fx-font-weight: bold;"
        })
        content.children.add(Label(
            "Click any link below to open it in your browser."
        ).apply {
            style = "-fx-text-fill: -fx-mid-text-color;"
        })

        for ((sectionTitle, links) in sections) {
            content.children.add(Separator())
            content.children.add(Label(sectionTitle).apply {
                style = "-fx-font-size: 14px; -fx-font-weight: bold;"
            })
            for ((label, url) in links) {
                val hyperlink = Hyperlink(label).apply {
                    setOnAction {
                        openUrl(url)
                    }
                    tooltip = Tooltip(url)
                }
                content.children.add(hyperlink)
            }
        }

        this.content = content
    }

    private fun openUrl(url: String) {
        Thread {
            try {
                ProcessBuilder("xdg-open", url)
                    .redirectErrorStream(true)
                    .start()
            } catch (e: Exception) {
                logger.warn { "xdg-open $url failed" }
            }
        }.apply { isDaemon = true }.start()
    }
}