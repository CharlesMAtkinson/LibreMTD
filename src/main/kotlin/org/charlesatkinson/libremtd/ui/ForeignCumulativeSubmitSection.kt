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

private val logger = KotlinLogging.logger {}

class ForeignCumulativeSubmitSection(
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
            title    = "Submit Foreign Property to HMRC — $taxYear",
            infoText = normalizeText(
                """
                    Submits your year-to-date foreign property income and expenses to HMRC,
                    one entry per foreign property that has income or expenses recorded for
                    this period.
                    {NL}{NL}
                    Each submission replaces the previous one for that property.
                    {NL}{NL}
                    If no foreign property has any income or expenses recorded for this
                    period, nothing is submitted.
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

            val buildResult = withContext(Dispatchers.IO) { buildForeignPropertyItems(taxYear) }

            when (buildResult) {
                is BuildResult.Blocked -> {
                    Platform.runLater {
                        submitBtn.isDisable = false
                        statusLabel.text    = "Cannot submit — see details"
                        statusLabel.setStatusStyle("status-error")
                        onStatusChange("Foreign submission blocked")
                        Dialogs.showError(
                            buildResult.errors.joinToString("\n"),
                            title = "Cannot submit foreign property",
                        )
                    }
                    return@launch
                }
                is BuildResult.Nothing -> {
                    Platform.runLater {
                        submitBtn.isDisable = false
                        statusLabel.text    = "Nothing to submit for $taxYear"
                        statusLabel.setStatusStyle("status-warning")
                        onStatusChange("No foreign property entries for this period")
                    }
                    return@launch
                }
                is BuildResult.Items -> {
                    logger.info {
                        "Foreign cumulative submit: taxYear=$taxYear fromDate=$fromDate toDate=$toDate " +
                                "properties=${buildResult.items.size}"
                    }

                    // TODO CONFIRM: does HmrcSettings carry a distinct foreign
                    // property business ID, separate from settings.businessId
                    // (which is used for the UK property submission)? UK and
                    // foreign property are normally separate MTD income
                    // sources, each with their own businessId. Using
                    // settings.businessId here as a placeholder — replace
                    // with the correct field once confirmed.
                    val result = PropertyForeignSubmissionClient(client).submitCumulative(
                        nino            = settings.nino,
                        businessId      = settings.businessIdForeign,
                        taxYear         = taxYear,
                        fromDate        = fromDate,
                        toDate          = toDate,
                        foreignProperty = buildResult.items,
                        context         = getContext(),
                    )

                    if (result.success) {
                        withContext(Dispatchers.IO) {
                            SubmissionRepository.record(
                                userId         = userId,
                                periodId       = null,
                                taxYear        = taxYear,
                                submissionType = "cumulative-foreign",
                                hmrcResponse   = result.statusCode.toString(),
                            )
                        }
                        logger.info { "Foreign submission succeeded" }
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
                            onStatusChange("Foreign submission failed — HTTP ${result.statusCode}")
                            Dialogs.showError(result.message, title = "Submission Failed — HTTP ${result.statusCode}")
                        }
                    }
                }
            }
        }
    }

    private sealed class BuildResult {
        data class Items(val items: List<ForeignPropertyItem>) : BuildResult()
        object Nothing : BuildResult()
        data class Blocked(val errors: List<String>) : BuildResult()
    }

    /**
     * True once taxYear reaches 2026-27, when HMRC switches from countryCode
     * to propertyId as the identifier within the foreignProperty array.
     * taxYear is always "YYYY-YY" — comparing the first 4 characters as a
     * string sorts identically to comparing the start year numerically.
     */
    private fun usesPropertyId(taxYear: String): Boolean =
        taxYear.take(4) >= "2026"

    private fun buildForeignPropertyItems(taxYear: String): BuildResult {
        val allIncome   = IncomePropertyForeignRepository.currentForeignPropertyIncomeForYear(userId, taxYear)
        val allExpenses = ExpensePropertyForeignRepository.currentForeignPropertyExpensesForYear(userId, taxYear)

        if (allIncome.isEmpty() && allExpenses.isEmpty()) return BuildResult.Nothing

        val foreignProperties = PropertyRepository.findByUser(userId)
            .filter { it.propertyType == PropertyType.FOREIGN }

        val items  = mutableListOf<ForeignPropertyItem>()
        val errors = mutableListOf<String>()

        for (property in foreignProperties) {
            val incomeEntries   = allIncome.filter { it.propertyId == property.id }
            val expenseEntries  = allExpenses.filter { it.propertyId == property.id }
            if (incomeEntries.isEmpty() && expenseEntries.isEmpty()) continue

            val identifierError: String?
            var countryCode: String? = null
            var hmrcPropertyId: String? = null

            if (usesPropertyId(taxYear)) {
                if (property.hmrcPropertyId == null) {
                    identifierError =
                        "${property.address}: not registered with HMRC yet — cannot include in a " +
                                "$taxYear submission (register the property with HMRC first)."
                } else {
                    hmrcPropertyId = property.hmrcPropertyId
                    identifierError = null
                }
            } else {
                if (property.countryCode == null) {
                    identifierError = "${property.address}: missing country code."
                } else {
                    countryCode = property.countryCode
                    identifierError = null
                }
            }

            if (identifierError != null) {
                errors += identifierError
                continue
            }

            val incomeBody = if (incomeEntries.isNotEmpty()) {
                val election = ForeignPropertyElectionRepository.currentOrRollForward(property.id, taxYear)
                if (election == null) {
                    errors += "${property.address}: Foreign Tax Credit Relief has not been set for " +
                            "$taxYear — set it before submitting."
                    null
                } else {
                    fun sum(dbKey: String) =
                        incomeEntries.filter { it.category == dbKey }.sumOf { it.amount }.takeIf { it > 0 }

                    ForeignPropertyIncomeBody(
                        rentIncome = ForeignRentIncomeBody(
                            rentAmount = incomeEntries
                                .filter { it.category == ForeignIncomeCategory.RentIncome.dbKey }
                                .sumOf { it.amount },
                        ),
                        foreignTaxCreditRelief           = election.foreignTaxCreditRelief,
                        premiumsOfLeaseGrant             = sum(ForeignIncomeCategory.PremiumsOfLeaseGrant.dbKey),
                        otherPropertyIncome               = sum(ForeignIncomeCategory.OtherPropertyIncome.dbKey),
                        foreignTaxPaidOrDeducted           = sum(ForeignIncomeCategory.ForeignTaxPaidOrDeducted.dbKey),
                        specialWithholdingTaxOrUkTaxPaid   = sum(ForeignIncomeCategory.SpecialWithholdingTaxOrUkTaxPaid.dbKey),
                    )
                }
            } else null

            val expensesBody = if (expenseEntries.isNotEmpty()) {
                fun sum(dbKey: String) =
                    expenseEntries.filter { it.category == dbKey }.sumOf { it.amount }.takeIf { it > 0 }

                ForeignPropertyExpensesBody(
                    premisesRunningCosts               = sum(ForeignExpenseCategory.PremisesRunningCosts.dbKey),
                    repairsAndMaintenance               = sum(ForeignExpenseCategory.RepairsAndMaintenance.dbKey),
                    financialCosts                       = sum(ForeignExpenseCategory.FinancialCosts.dbKey),
                    professionalFees                     = sum(ForeignExpenseCategory.ProfessionalFees.dbKey),
                    travelCosts                           = sum(ForeignExpenseCategory.TravelCosts.dbKey),
                    costOfServices                         = sum(ForeignExpenseCategory.CostOfServices.dbKey),
                    residentialFinancialCost               = sum(ForeignExpenseCategory.ResidentialFinancialCost.dbKey),
                    broughtFwdResidentialFinancialCost     = sum(ForeignExpenseCategory.BroughtFwdResidentialFinancialCost.dbKey),
                    other                                   = sum(ForeignExpenseCategory.Other.dbKey),
                )
            } else null

            if (incomeEntries.isNotEmpty() && incomeBody == null) continue // election error already recorded

            items += ForeignPropertyItem(
                countryCode    = countryCode,
                propertyId     = hmrcPropertyId,
                income         = incomeBody,
                expenses       = expensesBody,
            )
        }

        return when {
            errors.isNotEmpty() -> BuildResult.Blocked(errors)
            items.isEmpty()     -> BuildResult.Nothing
            else                -> BuildResult.Items(items)
        }
    }
}
