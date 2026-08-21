/*
 *
 *  * Copyright (C) 2026 Charles Michael Atkinson
 *  *
 *  * This program is free software: you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation, either version 3 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package org.charlesatkinson.libremtd.ui

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.charlesatkinson.libremtd.database.*
import org.charlesatkinson.libremtd.network.*
import org.charlesatkinson.libremtd.ui.components.*
import org.charlesatkinson.libremtd.utils.ApiResult

private val logger = KotlinLogging.logger {}

class CumulativeSubmitSection(
    private val scope:              CoroutineScope,
    private val userId:             Int,
    private val settingsRepository: SettingsRepository,
    private val getApiClient:       suspend () -> HmrcApiClient?,
    private val getContext:         () -> ClientContext,
    private val onStatusChange:     (String) -> Unit,
    private val onSubmitted:        (taxYear: String) -> Unit,
) {
    fun build(taxYear: String): VBox {
        val statusLabel = wrappingLabel("").apply { styleClass.add("hint-label") }

        val submitBtn = Button("Submit to HMRC").apply {
            styleClass.add("primary-action-button")
            padding = Insets(8.0, 16.0, 8.0, 16.0)
            setOnAction {
                isDisable        = true
                statusLabel.text = "Preparing…"
                statusLabel.setStatusStyle("status-warning")
                handleSubmit(this, statusLabel, taxYear)
            }
        }

        return buildSection(
            title    = "Submit UK property to HMRC — $taxYear",
            infoText = normalizeText(
                """
                    Submits your year-to-date UK property income and expenses to HMRC.
                    The submission covers from the start of the tax year to the end of the
                    most recent completed quarter.
                    {NL}{NL}
                    If no income or expenses have been entered for the period, a nil
                    submission is made so that HMRC marks the obligation as fulfilled.
                    {NL}{NL}
                    Each submission replaces the previous one.
                    {NL}{NL}
                    The deadlines for quarterly reports are the 7th of the month 
                    after the quarter end.
                """
            ),
            rows = listOf(
                HBox(12.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(submitBtn, statusLabel)
                },
            ),
        )
    }

    private fun handleSubmit(submitBtn: Button, statusLabel: Label, taxYear: String) {
        scope.launch {
            val connection = requireConnected(
                userId, settingsRepository, getApiClient,
                requireBusinessId = true,
            ) { message ->
                Platform.runLater {
                    submitBtn.isDisable = false
                    statusLabel.text    = message
                    statusLabel.setStatusStyle("status-error")
                    onStatusChange(message)
                }
            } ?: return@launch
            val (settings, client) = connection

            val toDate = latestEndedQuarterDate(taxYear)
            if (toDate == null) {
                Platform.runLater {
                    submitBtn.isDisable = false
                    statusLabel.text    = "No quarter has ended yet for $taxYear"
                    statusLabel.setStatusStyle("status-error")
                    onStatusChange("No quarter ended yet")
                }
                return@launch
            }

            val fromDate = taxYearStart(taxYear)
            val income   = aggregateIncome(taxYear)
            val expenses = aggregateExpenses(taxYear)

            logger.info {
                "Cumulative submit: taxYear=$taxYear fromDate=$fromDate toDate=$toDate " +
                        "income=${if (income != null) "present" else "nil"} " +
                        "expenses=${if (expenses != null) "present" else "nil"}"
            }

            val result = PropertyUkSubmissionClient(client).submitCumulative(
                nino       = settings.nino,
                businessId = settings.businessIdUk,
                taxYear    = taxYear,
                fromDate   = fromDate,
                toDate     = toDate,
                income     = income,
                expenses   = expenses,
                context    = getContext(),
            )

            if (result.success) {
                withContext(Dispatchers.IO) {
                    SubmissionRepository.record(
                        userId         = userId,
                        periodId       = null,
                        taxYear        = taxYear,
                        submissionType = "cumulative",
                        hmrcResponse   = result.statusCode.toString(),
                    )
                }
                logger.info { "Submission succeeded" }
                onSubmitted(taxYear)
            }

            Platform.runLater {
                submitBtn.isDisable = false
                if (result.success) {
                    statusLabel.text = result.message
                    statusLabel.setStatusStyle("status-success")
                    onStatusChange(result.message)
                } else {
                    statusLabel.text = "Failed (HTTP ${result.statusCode})"
                    statusLabel.setStatusStyle("status-error")
                    onStatusChange("Submission failed — HTTP ${result.statusCode}")
                    Dialogs.showError(result.message, title = "Submission Failed — HTTP ${result.statusCode}")
                }
            }
        }
    }

    private suspend fun aggregateIncome(taxYear: String): UkPropertyIncomeBody? =
        withContext(Dispatchers.IO) {
            val allEntries = IncomePropertyUkRepository.currentPropertyIncomeForYear(userId, taxYear)
            if (allEntries.isEmpty()) return@withContext null

            fun sum(dbKey: String) =
                allEntries.filter { it.category == dbKey }.sumOf { it.amount }.takeIf { it > 0 }

            val body = UkPropertyIncomeBody(
                periodAmount         = sum(IncomeCategory.TotalRentReceived.dbKey),
                taxDeducted          = sum(IncomeCategory.TaxDeducted.dbKey),
                premiumsOfLeaseGrant = sum(IncomeCategory.PremiumsOfLeaseGrant.dbKey),
                reversePremiums      = sum(IncomeCategory.ReversePremiums.dbKey),
                otherIncome          = sum(IncomeCategory.OtherIncome.dbKey),
            )

            if (listOfNotNull(
                    body.periodAmount, body.taxDeducted, body.premiumsOfLeaseGrant,
                    body.reversePremiums, body.otherIncome,
                ).isNotEmpty()) body else null
        }

    private suspend fun aggregateExpenses(taxYear: String): UkPropertyExpensesBody? =
        withContext(Dispatchers.IO) {
            val allEntries = ExpensePropertyUkRepository.currentPropertyExpensesForYear(userId, taxYear)
            if (allEntries.isEmpty()) return@withContext null

            fun sum(dbKey: String) =
                allEntries.filter { it.category == dbKey }.sumOf { it.amount }.takeIf { it > 0 }

            val body = UkPropertyExpensesBody(
                premisesRunningCosts                    = sum(ExpenseCategory.PremisesRunningCosts.dbKey),
                repairsAndMaintenance                   = sum(ExpenseCategory.RepairsAndMaintenance.dbKey),
                financialCosts                          = sum(ExpenseCategory.FinancialCosts.dbKey),
                professionalFees                        = sum(ExpenseCategory.ProfessionalFees.dbKey),
                costOfServices                          = sum(ExpenseCategory.CostOfServices.dbKey),
                residentialFinancialCost                = sum(ExpenseCategory.ResidentialFinanceCost.dbKey),
                residentialFinancialCostsCarriedForward = sum(ExpenseCategory.ResidentialFinanceCostCarriedForward.dbKey),
                travelCosts                             = sum(ExpenseCategory.TravelCosts.dbKey),
                other                                   = sum(ExpenseCategory.OtherExpenses.dbKey),
            )

            if (listOfNotNull(
                    body.premisesRunningCosts, body.repairsAndMaintenance, body.financialCosts,
                    body.professionalFees, body.costOfServices, body.residentialFinancialCost,
                    body.residentialFinancialCostsCarriedForward, body.travelCosts, body.other,
                ).isNotEmpty()) body else null
        }
}
