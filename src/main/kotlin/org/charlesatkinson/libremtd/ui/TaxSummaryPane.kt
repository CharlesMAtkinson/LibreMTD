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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.charlesatkinson.libremtd.database.SettingsRepository
import org.charlesatkinson.libremtd.network.ClientContext
import org.charlesatkinson.libremtd.network.HmrcApiClient
import org.charlesatkinson.libremtd.network.IndividualCalculationsClient
import org.charlesatkinson.libremtd.security.TokenStore
import org.charlesatkinson.libremtd.ui.components.TaxYearSelector
import org.charlesatkinson.libremtd.ui.components.previousCompletedTaxYear
import org.charlesatkinson.libremtd.utils.ApiResult
import org.charlesatkinson.libremtd.ui.components.wrappingLabel

private val logger = KotlinLogging.logger {}

class TaxSummaryPane(
    private val scope:              CoroutineScope,
    private val userId:             Int,
    private val settingsRepository: SettingsRepository,
    private val getApiClient:       suspend () -> HmrcApiClient?,
    private val getContext:         () -> ClientContext,
    private val onStatusChange:     (String) -> Unit,
) {

    val root: VBox

    // Initialise eagerly so the button can never fire with an uninitialised value.
    private var currentTaxYear: String = previousCompletedTaxYear()

    private val totalIncomeLabel       = wrappingLabel("—")
    private val totalDeductionsLabel   = wrappingLabel("—")
    private val personalAllowanceLabel = wrappingLabel("—")
    private val taxableIncomeLabel     = wrappingLabel("—")
    private val incomeTaxLabel         = wrappingLabel("—")
    private val class4NicLabel         = wrappingLabel("—")
    private val totalLiabilityLabel    = wrappingLabel("—")
    private val calculationIdLabel     = wrappingLabel("—")
    private val calculationTypeLabel   = wrappingLabel("—")

    // Hint label for the Tax & NICs section — updated whenever a result arrives.
    private val taxSectionHint = wrappingLabel("Estimated liability for $currentTaxYear").apply {
        styleClass.add("hint-label")
        style = "-fx-font-size: 11px;"
    }

    private val refreshBtn   = Button("Request tax calculation from HMRC").apply {
        styleClass.add("primary-action-button")
    }
    private val inlineStatus = wrappingLabel("").apply {
        styleClass.add("hint-label")
    }

    // taxYearSelector last, so all labels it touches are already initialised
    private val taxYearSelector = TaxYearSelector(
        userId   = userId,
        fallback = previousCompletedTaxYear(),
    ) { year ->
        if (year != currentTaxYear) {
            currentTaxYear = year
            clearSummaryLabels()
            inlineStatus.text = "Tax year changed — click the button to fetch calculation."
        } else {
            currentTaxYear = year
        }
    }

    init {
        root = buildUI()
    }

    private fun buildUI(): VBox {
        val content = VBox(20.0).apply {
            padding = Insets(4.0)
            children.addAll(
                wrappingLabel("Tax Summary").apply {
                    style = "-fx-font-size: 22px; -fx-font-weight: bold;"
                },
                wrappingLabel(
                    "An estimate of your income tax position, based on data submitted to HMRC."
                ).apply {
                    styleClass.add("hint-label")
                    isWrapText = true
                },
                buildYearSelector(),
                buildDisclaimerBanner(),
                buildIncomeSection(),
                buildTaxSection(),
                buildMetadataSection(),
                buildRefreshRow(),
            )
        }

        val scroll = ScrollPane(content).apply {
            isFitToWidth = true
            hbarPolicy   = ScrollPane.ScrollBarPolicy.NEVER
            vbarPolicy   = ScrollPane.ScrollBarPolicy.AS_NEEDED
            styleClass.add("edge-to-edge-scroll")
            VBox.setVgrow(this, Priority.ALWAYS)
        }

        return VBox(scroll).apply {
            VBox.setVgrow(scroll, Priority.ALWAYS)
        }
    }

    private fun buildYearSelector(): HBox = taxYearSelector.root

    private fun buildDisclaimerBanner(): HBox {
        return HBox(8.0).apply {
            padding   = Insets(10.0, 14.0, 10.0, 14.0)
            styleClass.add("disclaimer-banner")
            style     = "-fx-border-radius: 6; -fx-background-radius: 6;"
            alignment = Pos.CENTER_LEFT
            children.add(
                wrappingLabel(
                    "⚠  This is an estimate only. It is based on data submitted to HMRC to date " +
                            "and may not reflect your final tax liability. Do not use this figure for " +
                            "payment planning without consulting HMRC or a tax adviser."
                ).apply {
                    isWrapText = true
                    styleClass.add("disclaimer-text")
                    style      = "-fx-font-size: 11px;"
                }
            )
        }
    }

    private fun buildIncomeSection(): VBox {
        return buildSection(
            title    = "Income & Allowances",
            hintNode = wrappingLabel("Figures from the HMRC calculation").apply {
                styleClass.add("hint-label")
                style = "-fx-font-size: 11px;"
            },
            rows = listOf(
                buildSummaryRow("Total income received",         totalIncomeLabel,       bold = false),
                buildSummaryRow("Total allowances & deductions", totalDeductionsLabel,   bold = false),
                buildSummaryRow("Personal allowance",            personalAllowanceLabel, bold = false),
                buildSummaryRow("Taxable income",                taxableIncomeLabel,     bold = true),
            )
        )
    }

    private fun buildTaxSection(): VBox {
        // taxSectionHint is a field so it can be updated when results arrive.
        return buildSection(
            title    = "Tax & NICs",
            hintNode = taxSectionHint,
            rows     = listOf(
                buildSummaryRow("Income tax charged",        incomeTaxLabel,      bold = false),
                buildSummaryRow("Class 4 NICs",              class4NicLabel,      bold = false),
                Separator(),
                buildSummaryRow("Estimated total liability", totalLiabilityLabel, bold = true,
                    extraStyleClass = "total-value-label"),
            )
        )
    }

    private fun buildMetadataSection(): VBox {
        return buildSection(
            title    = "Calculation Details",
            hintNode = wrappingLabel("Technical reference from HMRC").apply {
                styleClass.add("hint-label")
                style = "-fx-font-size: 11px;"
            },
            rows = listOf(
                buildSummaryRow("Calculation ID",   calculationIdLabel,   bold = false),
                buildSummaryRow("Calculation type", calculationTypeLabel, bold = false),
            )
        )
    }

    private fun buildRefreshRow(): HBox {
        refreshBtn.setOnAction { triggerCalculation() }
        return HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(refreshBtn, inlineStatus)
        }
    }

    private fun triggerCalculation() {
        val taxYear = currentTaxYear          // snapshot on the FX thread before launching

        refreshBtn.isDisable = true
        inlineStatus.text    = "Checking connection…"
        onStatusChange("Requesting tax calculation from HMRC…")
        logger.info { "Tax calculation requested for $taxYear" }

        scope.launch {
            try {
                if (TokenStore.isExpired() || TokenStore.getAccessToken() == null) {
                    Platform.runLater {
                        refreshBtn.isDisable = false
                        inlineStatus.text    = "Not connected to HMRC — use HMRC Connect first"
                        onStatusChange("Not connected to HMRC")
                    }
                    logger.warn { "Tax calculation aborted: token missing or expired" }
                    return@launch
                }

                val client = getApiClient() ?: run {
                    Platform.runLater {
                        refreshBtn.isDisable = false
                        inlineStatus.text    = "Client ID / Secret not configured — check Settings"
                        onStatusChange("API client not available")
                    }
                    logger.warn { "Tax calculation aborted: API client unavailable" }
                    return@launch
                }

                val settings = withContext(Dispatchers.IO) {
                    settingsRepository.load(userId)
                }
                val nino = settings?.nino?.takeIf { it.isNotBlank() } ?: run {
                    Platform.runLater {
                        refreshBtn.isDisable = false
                        inlineStatus.text    = "NINO not set — add it in Settings"
                        onStatusChange("NINO not configured")
                    }
                    logger.warn { "Tax calculation aborted: NINO not configured" }
                    return@launch
                }

                Platform.runLater {
                    inlineStatus.text = "Triggering calculation for $taxYear…"
                }
                logger.info { "Calling IndividualCalculationsClient for nino=$nino taxYear=$taxYear" }

                val result = IndividualCalculationsClient(client).triggerAndFetch(
                    nino         = nino,
                    taxYear      = taxYear,
                    context      = getContext(),
                    testScenario = null,
                )

                Platform.runLater {
                    refreshBtn.isDisable = false
                    when (result) {
                        is ApiResult.Failure -> {
                            inlineStatus.text = "Error: ${result.message}"
                            onStatusChange("Tax calculation failed")
                            logger.error { "Tax calculation failed for $taxYear: ${result.message}" }
                        }
                        is ApiResult.Success -> {
                            val s = result.data
                            totalIncomeLabel.text       = formatGbp(s.totalIncome)
                            totalDeductionsLabel.text   = formatGbp(s.totalDeductions)
                            personalAllowanceLabel.text = formatGbp(s.personalAllowance)
                            taxableIncomeLabel.text     = formatGbp(s.taxableIncome)
                            incomeTaxLabel.text         = formatGbp(s.incomeTax)
                            class4NicLabel.text         = formatGbp(s.class4Nics)
                            totalLiabilityLabel.text    = formatGbp(s.totalLiability)
                            calculationIdLabel.text     = s.calculationId
                            calculationTypeLabel.text   = s.calculationType
                            taxSectionHint.text         = "Estimated liability for ${s.taxYear}"
                            inlineStatus.text           = "Updated ✓  (${s.taxYear})"
                            onStatusChange("Tax calculation updated ✓")
                            logger.info {
                                "Tax calculation loaded: id=${s.calculationId} " +
                                        "taxYear=${s.taxYear} type=${s.calculationType} " +
                                        "totalLiability=${s.totalLiability}"
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Unexpected error during tax calculation for $taxYear" }
                Platform.runLater {
                    refreshBtn.isDisable = false
                    inlineStatus.text    = "Unexpected error — see log for details"
                    onStatusChange("Tax calculation error")
                }
            }
        }
    }

    private fun clearSummaryLabels() {
        listOf(
            totalIncomeLabel, totalDeductionsLabel, personalAllowanceLabel,
            taxableIncomeLabel, incomeTaxLabel, class4NicLabel,
            totalLiabilityLabel, calculationIdLabel, calculationTypeLabel,
        ).forEach { it.text = "—" }
    }

    private fun formatGbp(amount: Double) = "£%,.2f".format(amount)

    private fun buildSection(
        title:    String,
        hintNode: javafx.scene.Node,
        rows:     List<javafx.scene.Node>,
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
                        hintNode,
                    )
                },
                Separator(),
                *rows.toTypedArray(),
            )
        }
    }

    private fun buildSummaryRow(
        labelText:       String,
        valueLabel:      Label,
        bold:            Boolean,
        extraStyleClass: String? = null,
    ): HBox {
        return HBox().apply {
            padding   = Insets(3.0, 0.0, 3.0, 0.0)
            alignment = Pos.CENTER_LEFT
            children.addAll(
                wrappingLabel(labelText).apply {
                    prefWidth = 280.0
                    if (bold) style = "-fx-font-weight: bold;"
                    else styleClass.add("row-label")
                },
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                valueLabel.apply {
                    if (bold) style = "-fx-font-weight: bold;"
                    if (extraStyleClass != null) styleClass.add(extraStyleClass)
                },
            )
        }
    }
}