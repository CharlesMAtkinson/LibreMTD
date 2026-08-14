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
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.charlesatkinson.libremtd.api.models.BsasPostExpenses
import org.charlesatkinson.libremtd.api.models.BsasPostIncome
import org.charlesatkinson.libremtd.api.models.BsasPostPayload
import org.charlesatkinson.libremtd.api.models.ForeignPropertyBsasExpenses
import org.charlesatkinson.libremtd.api.models.ForeignPropertyBsasIncome
import org.charlesatkinson.libremtd.api.models.ForeignPropertyBsasPostCountry
import org.charlesatkinson.libremtd.api.models.ForeignPropertyBsasPostPayload
import org.charlesatkinson.libremtd.api.models.ForeignPropertyBsasResponse
import org.charlesatkinson.libremtd.api.models.UkPropertyBsasResponse
import org.charlesatkinson.libremtd.database.*
import org.charlesatkinson.libremtd.network.*
import org.charlesatkinson.libremtd.ui.components.*
import org.charlesatkinson.libremtd.utils.ApiResult
import org.charlesatkinson.libremtd.utils.Config

private val logger = KotlinLogging.logger {}

class FinalDeclarationSection(
    private val scope:              CoroutineScope,
    private val userId:             Int,
    private val settingsRepository: SettingsRepository,
    private val getApiClient:       suspend () -> HmrcApiClient?,
    private val getContext:         () -> ClientContext,
    private val onStatusChange:     (String) -> Unit,
    private val getObligations:     () -> List<Obligation>,
) {
    private val isSandbox: Boolean = Config.hmrcSandbox

    fun build(taxYear: String): VBox {
        val dynamicArea = VBox(10.0).apply {
            maxWidth = Double.MAX_VALUE
        }
        StateMachine(taxYear, dynamicArea).start()

        return buildSection(
            title    = "Final Declaration — $taxYear",
            infoText = normalizeText(
                """
                    Submits your self assessment final declaration to HMRC for the $taxYear
                    tax year. All four quarterly obligations must be fulfilled before HMRC will
                    accept the declaration. You may want to review your figures in the Tax 
                    Summary pane before proceeding.
                    {NL}{NL}
                    The deadline for final declaration is 31 January following the end of the
                    tax year.
                """
            ),
            rows = listOf(dynamicArea),
        )
    }

    private sealed class FinalDeclState {
        object Idle : FinalDeclState()
        object HintShown : FinalDeclState()
        object Triggering : FinalDeclState()
        data class AwaitingBsasReview(
            val summary:     TaxCalculationSummary,
            val bsas:        UkPropertyBsasResponse,
            val foreignBsas: ForeignPropertyBsasResponse? = null,
        ) : FinalDeclState()
        object BsasSubmitting : FinalDeclState()
        data class AwaitingForeignBsasReview(
            val summary: TaxCalculationSummary,
            val bsas:    ForeignPropertyBsasResponse,
        ) : FinalDeclState()
        object ForeignBsasSubmitting : FinalDeclState()
        data class AwaitingConfirm(val summary: TaxCalculationSummary) : FinalDeclState()
        object Submitting : FinalDeclState()
        object Done : FinalDeclState()
        data class Failed(val message: String) : FinalDeclState()
    }

    private inner class StateMachine(
        private val taxYear:     String,
        private val dynamicArea: VBox,
    ) {
        private var state: FinalDeclState = FinalDeclState.Idle

        fun start() = render()

        private fun apply(next: FinalDeclState) {
            state = next
            render()
        }

        private fun render() {
            dynamicArea.children.setAll(buildFor(state))
        }

        private fun buildFor(state: FinalDeclState): javafx.scene.Node = when (state) {
            is FinalDeclState.Idle                      -> buildIdle()
            is FinalDeclState.HintShown                 -> buildHintShown()
            is FinalDeclState.Triggering                -> buildBusyLabel("Requesting calculation from HMRC…")
            is FinalDeclState.AwaitingBsasReview        -> buildBsasReview(state)
            is FinalDeclState.BsasSubmitting            -> buildBusyLabel("Submitting UK property accounting adjustments to HMRC…")
            is FinalDeclState.AwaitingForeignBsasReview -> buildForeignBsasReview(state)
            is FinalDeclState.ForeignBsasSubmitting     -> buildBusyLabel("Submitting foreign property accounting adjustments to HMRC…")
            is FinalDeclState.AwaitingConfirm           -> buildAwaitingConfirm(state)
            is FinalDeclState.Submitting                -> buildBusyLabel("Submitting final declaration to HMRC…")
            is FinalDeclState.Done                      -> wrappingLabel("✓ Final declaration submitted successfully.")
                .apply { styleClass.add("status-success") }
            is FinalDeclState.Failed                    -> buildFailed(state)
        }

        private fun buildBusyLabel(text: String) =
            wrappingLabel(text).apply { styleClass.add("status-warning") }

        // ── Shared hint text ──────────────────────────────────────────────────

        private fun bsasHintText(propertyType: String) = normalizeText(
            """
                The summary has figures HMRC has calculated from your $propertyType submissions.
                {NL}{NL}
                The income or expense adjustment fields are what HMRC call Business Source
                Adjustable Summary (BSAS). You can use them to correct your submitted figures
                at year end. Most users with straightforward property business do not need to
                make any adjustments and can click "No adjustments".
                {NL}{NL}
                No definitive list of possible adjustments was found. Examples include
                separating the revenue and capital portions of a maintenance invoice and
                correcting an income or expense item which was wrongly entered before the
                last quarterly submission. If you make any adjustments you should keep an
                itemised breakdown in case HMRC question them.
                {NL}{NL}
                Adjustments are changes, not actual amounts. For example, if you submitted
                £250 but the correct figure is £200, enter -50.
                {NL}{NL}
                If any income or expense adjustment fields already have figures, they are
                from an earlier BSAS submission. You can edit them. On submission, the new
                values replace the old values held by HMRC. To remove a prior adjustment,
                delete the figure — a deleted or blank field with a prior value will be sent
                to HMRC as zero.
                {NL}{NL}
                If you add or edit any adjustments, click "Submit adjustments" otherwise
                click "No adjustments".
            """
        )

        // ── Idle ──────────────────────────────────────────────────────────────

        private fun buildIdle(): HBox {
            val declareBtn = Button("Make final declaration").apply {
                styleClass.add("primary-action-button")
                padding = Insets(8.0, 16.0, 8.0, 16.0)
                setOnAction { apply(FinalDeclState.HintShown) }
            }
            return HBox(declareBtn).apply { alignment = Pos.CENTER_LEFT }
        }

        // ── HintShown ─────────────────────────────────────────────────────────

        private fun buildHintShown(): VBox {
            val hint = hintLabel(
                "Before continuing, you may want to review your figures in the " +
                        "Tax Summary pane. When you click Continue, LibreMTD will request " +
                        "a final tax calculation from HMRC and show you the key figures " +
                        "for confirmation before anything is submitted."
            )

            val continueBtn = Button("Continue").apply {
                styleClass.add("primary-action-button")
                padding = Insets(8.0, 16.0, 8.0, 16.0)
                setOnAction {
                    val unfulfilled = getObligations().filter { it.status != ObligationStatus.Fulfilled }
                    if (unfulfilled.isNotEmpty()) {
                        Dialogs.showError(
                            "${unfulfilled.size} quarter(s) are not yet fulfilled:\n\n" +
                                    unfulfilled.joinToString("\n") { "  ${it.periodKey}" } +
                                    "\n\nSubmit to HMRC for each open quarter before " +
                                    "making your final declaration.",
                            title = "Declaration Cannot Proceed — Obligations Not Fulfilled",
                        )
                        return@setOnAction
                    }
                    apply(FinalDeclState.Triggering)
                    triggerIntentToFinalise(taxYear)
                }
            }

            val cancelBtn = Button("Cancel").apply {
                styleClass.add("secondary-action-button")
                setOnAction { apply(FinalDeclState.Idle) }
            }

            return VBox(10.0).apply {
                maxWidth = Double.MAX_VALUE
                children.addAll(
                    hint,
                    HBox(8.0).apply {
                        alignment = Pos.CENTER_LEFT
                        children.addAll(continueBtn, cancelBtn)
                    },
                )
            }
        }

        // ── UK property BSAS review ───────────────────────────────────────────

        private fun buildBsasReview(state: FinalDeclState.AwaitingBsasReview): VBox {
            val bsas = state.bsas
            val calc = bsas.adjustableSummaryCalculation

            fun nextState(summary: TaxCalculationSummary): FinalDeclState =
                if (state.foreignBsas != null)
                    FinalDeclState.AwaitingForeignBsasReview(summary, state.foreignBsas)
                else
                    FinalDeclState.AwaitingConfirm(summary)

            fun summaryRow(label: String, value: Double?) =
                HBox(12.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(
                        Label(label).apply { prefWidth = 280.0 },
                        Label(if (value != null) "£%,.2f".format(value) else "—"),
                    )
                }

            fun fieldRow(label: String, field: TextField) =
                HBox(12.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(
                        Label(label).apply { prefWidth = 280.0 },
                        field,
                    )
                }

            fun prefilled(value: Double?) =
                TextField(if (value != null) "%.2f".format(value) else "").apply { prefWidth = 120.0 }

            val existingAdj = bsas.adjustments
            val existingInc = existingAdj?.income
            val existingExp = existingAdj?.expenses

            val fTotalRents   = prefilled(existingInc?.totalRentsReceived)
            val fPremiums     = prefilled(existingInc?.premiumsOfLeaseGrant)
            val fReverse      = prefilled(existingInc?.reversePremiums)
            val fOtherIncome  = prefilled(existingInc?.otherPropertyIncome)
            val fPremises     = prefilled(existingExp?.premisesRunningCosts)
            val fRepairs      = prefilled(existingExp?.repairsAndMaintenance)
            val fFinancial    = prefilled(existingExp?.financialCosts)
            val fProfessional = prefilled(existingExp?.professionalFees)
            val fCostServices = prefilled(existingExp?.costOfServices)
            val fResidential  = prefilled(existingExp?.residentialFinancialCost)
            val fOtherExp     = prefilled(existingExp?.other)
            val fTravel       = prefilled(existingExp?.travelCosts)

            val allFields = listOf(
                fTotalRents, fPremiums, fReverse, fOtherIncome,
                fPremises, fRepairs, fFinancial, fProfessional,
                fCostServices, fResidential, fOtherExp, fTravel,
            )
            val initialValues: Map<TextField, String> = allFields.associateWith { it.text }

            val validationLabel = wrappingLabel("").apply { styleClass.add("status-error") }

            // A cleared field that had a prior value is sent as 0 to explicitly
            // remove the prior adjustment. A field that was always blank is omitted.
            fun TextField.effectiveValue(): Double? {
                val trimmed = text.trim()
                return when {
                    trimmed.isNotEmpty()                -> trimmed.toDoubleOrNull()
                    initialValues[this].isNullOrEmpty() -> null
                    else                                -> 0.0
                }
            }

            fun buildPayload(): BsasPostPayload? {
                val badFields = allFields.filter {
                    it.text.trim().isNotEmpty() && it.text.trim().toDoubleOrNull() == null
                }
                if (badFields.isNotEmpty()) return null

                val inc = BsasPostIncome(
                    totalRentsReceived   = fTotalRents.effectiveValue(),
                    premiumsOfLeaseGrant = fPremiums.effectiveValue(),
                    reversePremiums      = fReverse.effectiveValue(),
                    otherPropertyIncome  = fOtherIncome.effectiveValue(),
                )
                val exp = BsasPostExpenses(
                    premisesRunningCosts     = fPremises.effectiveValue(),
                    repairsAndMaintenance    = fRepairs.effectiveValue(),
                    financialCosts           = fFinancial.effectiveValue(),
                    professionalFees         = fProfessional.effectiveValue(),
                    costOfServices           = fCostServices.effectiveValue(),
                    residentialFinancialCost = fResidential.effectiveValue(),
                    other                    = fOtherExp.effectiveValue(),
                    travelCosts              = fTravel.effectiveValue(),
                )
                val hasIncome = listOfNotNull(
                    inc.totalRentsReceived, inc.premiumsOfLeaseGrant,
                    inc.reversePremiums, inc.otherPropertyIncome,
                ).isNotEmpty()
                val hasExpenses = listOfNotNull(
                    exp.premisesRunningCosts, exp.repairsAndMaintenance, exp.financialCosts,
                    exp.professionalFees, exp.costOfServices, exp.residentialFinancialCost,
                    exp.other, exp.travelCosts,
                ).isNotEmpty()
                return BsasPostPayload.Adjustments(
                    income   = if (hasIncome)   inc else null,
                    expenses = if (hasExpenses) exp else null,
                )
            }

            val foreignHint = if (state.foreignBsas != null)
                hintLabel("After submitting UK property adjustments, you will review foreign property adjustments.")
            else null

            val submitBtn = Button("Submit adjustments").apply {
                styleClass.add("secondary-action-button")
                padding = Insets(8.0, 16.0, 8.0, 16.0)
                setOnAction {
                    val payload = buildPayload()
                    if (payload == null) {
                        validationLabel.text = "One or more fields contain invalid numbers."
                        return@setOnAction
                    }
                    validationLabel.text = ""
                    apply(FinalDeclState.BsasSubmitting)
                    submitUkBsasAdjustments(taxYear, bsas.metadata.calculationId,
                        payload, state.summary, ::nextState)
                }
            }

            val skipBtn = Button("No adjustments").apply {
                styleClass.add("primary-action-button")
                padding = Insets(8.0, 16.0, 8.0, 16.0)
                setOnAction { apply(nextState(state.summary)) }
            }

            val cancelBtn = Button("Cancel").apply {
                styleClass.add("secondary-action-button")
                setOnAction { apply(FinalDeclState.Idle) }
            }

            fun updateButtonStyles() {
                val anyModified = allFields.any { it.text != initialValues[it] }
                submitBtn.styleClass.removeAll("primary-action-button", "secondary-action-button")
                skipBtn.styleClass.removeAll("primary-action-button", "secondary-action-button")
                if (anyModified) {
                    submitBtn.styleClass.add("primary-action-button")
                    skipBtn.isDisable = true
                } else {
                    submitBtn.styleClass.add("secondary-action-button")
                    skipBtn.styleClass.add("primary-action-button")
                    skipBtn.isDisable = false
                }
            }

            allFields.forEach { field ->
                field.textProperty().addListener { _, _, _ -> updateButtonStyles() }
            }
            updateButtonStyles()

            val stepLabel = if (state.foreignBsas != null)
                "Step 1 of 3 — Review and adjust UK property figures"
            else
                "Step 1 of 2 — Review and adjust your figures"

            return VBox(10.0).apply {
                maxWidth = Double.MAX_VALUE
                children.addAll(
                    wrappingLabel(stepLabel).apply { style = "-fx-font-weight: bold;" },
                    hintLabel(bsasHintText("UK property")),
                    Separator(),
                    wrappingLabel("HMRC calculated summary").apply { style = "-fx-font-weight: bold; -fx-font-size: 13px;" },
                    summaryRow("Total income",   calc?.totalIncome),
                    summaryRow("Total expenses", calc?.totalExpenses),
                    summaryRow("Net profit",     calc?.netProfit),
                    summaryRow("Taxable profit", calc?.taxableProfit),
                    Separator(),
                    wrappingLabel("Income adjustments").apply { style = "-fx-font-weight: bold; -fx-font-size: 13px;" },
                    fieldRow("Total rents received",    fTotalRents),
                    fieldRow("Premiums of lease grant", fPremiums),
                    fieldRow("Reverse premiums",        fReverse),
                    fieldRow("Other property income",   fOtherIncome),
                    Separator(),
                    wrappingLabel("Expense adjustments").apply { style = "-fx-font-weight: bold; -fx-font-size: 13px;" },
                    fieldRow("Premises running costs",     fPremises),
                    fieldRow("Repairs and maintenance",    fRepairs),
                    fieldRow("Financial costs",            fFinancial),
                    fieldRow("Professional fees",          fProfessional),
                    fieldRow("Cost of services",           fCostServices),
                    fieldRow("Residential financial cost", fResidential),
                    fieldRow("Other expenses",             fOtherExp),
                    fieldRow("Travel costs",               fTravel),
                    Separator(),
                    validationLabel,
                )
                if (foreignHint != null) children.add(foreignHint)
                children.add(
                    HBox(8.0).apply {
                        alignment = Pos.CENTER_LEFT
                        children.addAll(submitBtn, skipBtn, cancelBtn)
                    }
                )
            }
        }

        // ── Foreign property BSAS review ─────────────────────────────────────

        private fun buildForeignBsasReview(state: FinalDeclState.AwaitingForeignBsasReview): VBox {
            val bsas      = state.bsas
            val calc      = bsas.adjustableSummaryCalculation
            val countries = calc?.countryLevelDetail.orEmpty()

            data class CountryFieldSet(
                val countryCode:   String,
                val fTotalRents:   TextField,
                val fPremiums:     TextField,
                val fOtherIncome:  TextField,
                val fPremises:     TextField,
                val fRepairs:      TextField,
                val fFinancial:    TextField,
                val fProfessional: TextField,
                val fCostServices: TextField,
                val fTravel:       TextField,
                val fResidential:  TextField,
                val fOtherExp:     TextField,
            )

            fun prefilled(value: Double?) =
                TextField(if (value != null) "%.2f".format(value) else "").apply { prefWidth = 120.0 }

            val existingByCountry: Map<String, ForeignPropertyBsasExpenses?> =
                bsas.adjustments?.countryLevelDetail
                    ?.associate { it.countryCode to it.expenses }
                    ?: emptyMap()

            val existingIncByCountry: Map<String, ForeignPropertyBsasIncome?> =
                bsas.adjustments?.countryLevelDetail
                    ?.associate { it.countryCode to it.income }
                    ?: emptyMap()

            val fieldSets: List<CountryFieldSet> = countries.map { country ->
                val code = country.countryCode
                val inc  = existingIncByCountry[code]
                val exp  = existingByCountry[code]
                CountryFieldSet(
                    countryCode   = code,
                    fTotalRents   = prefilled(inc?.totalRentsReceived),
                    fPremiums     = prefilled(inc?.premiumsOfLeaseGrant),
                    fOtherIncome  = prefilled(inc?.otherPropertyIncome),
                    fPremises     = prefilled(exp?.premisesRunningCosts),
                    fRepairs      = prefilled(exp?.repairsAndMaintenance),
                    fFinancial    = prefilled(exp?.financialCosts),
                    fProfessional = prefilled(exp?.professionalFees),
                    fCostServices = prefilled(exp?.costOfServices),
                    fTravel       = prefilled(exp?.travelCosts),
                    fResidential  = prefilled(exp?.residentialFinancialCost),
                    fOtherExp     = prefilled(exp?.other),
                )
            }

            val allFields: List<TextField> = fieldSets.flatMap {
                listOf(it.fTotalRents, it.fPremiums, it.fOtherIncome,
                    it.fPremises, it.fRepairs, it.fFinancial, it.fProfessional,
                    it.fCostServices, it.fTravel, it.fResidential, it.fOtherExp)
            }
            val initialValues: Map<TextField, String> = allFields.associateWith { it.text }

            val validationLabel = wrappingLabel("").apply { styleClass.add("status-error") }

            fun TextField.effectiveValue(): Double? {
                val trimmed = text.trim()
                return when {
                    trimmed.isNotEmpty()                -> trimmed.toDoubleOrNull()
                    initialValues[this].isNullOrEmpty() -> null
                    else                                -> 0.0
                }
            }

            fun buildPayload(): ForeignPropertyBsasPostPayload? {
                val badFields = allFields.filter {
                    it.text.trim().isNotEmpty() && it.text.trim().toDoubleOrNull() == null
                }
                if (badFields.isNotEmpty()) return null

                val countryList = fieldSets.map { fs ->
                    val inc = ForeignPropertyBsasIncome(
                        totalRentsReceived   = fs.fTotalRents.effectiveValue(),
                        premiumsOfLeaseGrant = fs.fPremiums.effectiveValue(),
                        otherPropertyIncome  = fs.fOtherIncome.effectiveValue(),
                    )
                    val exp = ForeignPropertyBsasExpenses(
                        premisesRunningCosts     = fs.fPremises.effectiveValue(),
                        repairsAndMaintenance    = fs.fRepairs.effectiveValue(),
                        financialCosts           = fs.fFinancial.effectiveValue(),
                        professionalFees         = fs.fProfessional.effectiveValue(),
                        travelCosts              = fs.fTravel.effectiveValue(),
                        costOfServices           = fs.fCostServices.effectiveValue(),
                        residentialFinancialCost = fs.fResidential.effectiveValue(),
                        other                    = fs.fOtherExp.effectiveValue(),
                    )
                    val hasIncome = listOfNotNull(
                        inc.totalRentsReceived, inc.premiumsOfLeaseGrant, inc.otherPropertyIncome,
                    ).isNotEmpty()
                    val hasExpenses = listOfNotNull(
                        exp.premisesRunningCosts, exp.repairsAndMaintenance, exp.financialCosts,
                        exp.professionalFees, exp.travelCosts, exp.costOfServices,
                        exp.residentialFinancialCost, exp.other,
                    ).isNotEmpty()
                    ForeignPropertyBsasPostCountry(
                        countryCode = fs.countryCode,
                        income      = if (hasIncome)   inc else null,
                        expenses    = if (hasExpenses) exp else null,
                    )
                }.filter { it.income != null || it.expenses != null }

                return ForeignPropertyBsasPostPayload(countryLevelDetail = countryList)
            }

            fun fieldRow(label: String, field: TextField) =
                HBox(12.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(
                        Label(label).apply { prefWidth = 280.0 },
                        field,
                    )
                }

            fun summaryRow(label: String, value: Double?) =
                HBox(12.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(
                        Label(label).apply { prefWidth = 280.0 },
                        Label(if (value != null) "£%,.2f".format(value) else "—"),
                    )
                }

            val submitBtn = Button("Submit adjustments").apply {
                styleClass.add("secondary-action-button")
                padding = Insets(8.0, 16.0, 8.0, 16.0)
                setOnAction {
                    val payload = buildPayload()
                    if (payload == null) {
                        validationLabel.text = "One or more fields contain invalid numbers."
                        return@setOnAction
                    }
                    if (payload.countryLevelDetail.isEmpty()) {
                        apply(FinalDeclState.AwaitingConfirm(state.summary))
                        return@setOnAction
                    }
                    validationLabel.text = ""
                    apply(FinalDeclState.ForeignBsasSubmitting)
                    submitForeignBsasAdjustments(taxYear, bsas.metadata.calculationId, payload, state.summary)
                }
            }

            val skipBtn = Button("No adjustments").apply {
                styleClass.add("primary-action-button")
                padding = Insets(8.0, 16.0, 8.0, 16.0)
                setOnAction { apply(FinalDeclState.AwaitingConfirm(state.summary)) }
            }

            val cancelBtn = Button("Cancel").apply {
                styleClass.add("secondary-action-button")
                setOnAction { apply(FinalDeclState.Idle) }
            }

            fun updateButtonStyles() {
                val anyModified = allFields.any { it.text != initialValues[it] }
                submitBtn.styleClass.removeAll("primary-action-button", "secondary-action-button")
                skipBtn.styleClass.removeAll("primary-action-button", "secondary-action-button")
                if (anyModified) {
                    submitBtn.styleClass.add("primary-action-button")
                    skipBtn.isDisable = true
                } else {
                    submitBtn.styleClass.add("secondary-action-button")
                    skipBtn.styleClass.add("primary-action-button")
                    skipBtn.isDisable = false
                }
            }

            allFields.forEach { field ->
                field.textProperty().addListener { _, _, _ -> updateButtonStyles() }
            }
            updateButtonStyles()

            return VBox(10.0).apply {
                maxWidth = Double.MAX_VALUE
                children.addAll(
                    wrappingLabel("Step 2 of 3 — Review and adjust foreign property figures").apply {
                        style = "-fx-font-weight: bold;"
                    },
                    hintLabel(bsasHintText("foreign property")),
                    Separator(),
                    summaryRow("Total income",   calc?.totalIncome),
                    summaryRow("Total expenses", calc?.totalExpenses),
                    summaryRow("Net profit",     calc?.netProfit),
                    summaryRow("Taxable profit", calc?.taxableProfit),
                )
                fieldSets.forEach { fs ->
                    children.add(Separator())
                    children.add(
                        wrappingLabel("Country: ${fs.countryCode}").apply {
                            style = "-fx-font-weight: bold; -fx-font-size: 13px;"
                        }
                    )
                    children.add(wrappingLabel("Income adjustments").apply {
                        style = "-fx-font-size: 12px; -fx-font-weight: bold;"
                    })
                    children.addAll(
                        fieldRow("Total rents received",    fs.fTotalRents),
                        fieldRow("Premiums of lease grant", fs.fPremiums),
                        fieldRow("Other property income",   fs.fOtherIncome),
                    )
                    children.add(wrappingLabel("Expense adjustments").apply {
                        style = "-fx-font-size: 12px; -fx-font-weight: bold;"
                    })
                    children.addAll(
                        fieldRow("Premises running costs",     fs.fPremises),
                        fieldRow("Repairs and maintenance",    fs.fRepairs),
                        fieldRow("Financial costs",            fs.fFinancial),
                        fieldRow("Professional fees",          fs.fProfessional),
                        fieldRow("Cost of services",           fs.fCostServices),
                        fieldRow("Travel costs",               fs.fTravel),
                        fieldRow("Residential financial cost", fs.fResidential),
                        fieldRow("Other expenses",             fs.fOtherExp),
                    )
                }
                children.addAll(
                    Separator(),
                    validationLabel,
                    HBox(8.0).apply {
                        alignment = Pos.CENTER_LEFT
                        children.addAll(submitBtn, skipBtn, cancelBtn)
                    },
                )
            }
        }

        // ── Awaiting confirm ──────────────────────────────────────────────────

        private fun buildAwaitingConfirm(state: FinalDeclState.AwaitingConfirm): VBox {
            val s = state.summary

            fun row(label: String, value: Double) =
                HBox(12.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(
                        Label(label).apply { prefWidth = 260.0 },
                        Label("£%,.2f".format(value)),
                    )
                }

            val copyBtn = Button("Copy").apply {
                styleClass.add("secondary-action-button")
                setOnAction {
                    val text = buildString {
                        appendLine("Calculation summary (intent to finalise)")
                        appendLine("Total income received\t£%,.2f".format(s.totalIncome))
                        appendLine("Total allowances/deductions\t£%,.2f".format(s.totalDeductions))
                        appendLine("Taxable income\t£%,.2f".format(s.taxableIncome))
                        appendLine("Personal allowance\t£%,.2f".format(s.personalAllowance))
                        appendLine("Income tax charged\t£%,.2f".format(s.incomeTax))
                        appendLine("Class 4 NICs\t£%,.2f".format(s.class4Nics))
                        appendLine("Total tax liability\t£%,.2f".format(s.totalLiability))
                    }
                    val clipboard = javafx.scene.input.Clipboard.getSystemClipboard()
                    val content   = javafx.scene.input.ClipboardContent()
                    content.putString(text)
                    clipboard.setContent(content)
                }
            }

            val summaryBox = VBox(6.0).apply {
                padding = Insets(8.0, 0.0, 8.0, 0.0)
                children.addAll(
                    HBox(8.0).apply {
                        alignment = Pos.CENTER_LEFT
                        children.addAll(
                            wrappingLabel("Calculation summary (intent to finalise)").apply {
                                style = "-fx-font-weight: bold;"
                            },
                            copyBtn,
                        )
                    },
                    row("Total income received",       s.totalIncome),
                    row("Total allowances/deductions", s.totalDeductions),
                    row("Taxable income",              s.taxableIncome),
                    row("Personal allowance",          s.personalAllowance),
                    row("Income tax charged",          s.incomeTax),
                    row("Class 4 NICs",                s.class4Nics),
                    row("Total tax liability",         s.totalLiability),
                )
            }

            val confirmBtn = Button("Confirm and submit").apply {
                styleClass.add("primary-action-button")
                padding = Insets(8.0, 16.0, 8.0, 16.0)
                setOnAction {
                    apply(FinalDeclState.Submitting)
                    submitDeclaration(taxYear, s.calculationId)
                }
            }

            val cancelBtn = Button("Cancel").apply {
                styleClass.add("secondary-action-button")
                setOnAction { apply(FinalDeclState.Idle) }
            }

            return VBox(10.0).apply {
                children.addAll(
                    summaryBox,
                    hintLabel(
                        "Review the figures above. Click \"Confirm and submit\" to send your " +
                                "final declaration to HMRC. This action cannot be undone."
                    ),
                    HBox(8.0).apply {
                        alignment = Pos.CENTER_LEFT
                        children.addAll(confirmBtn, cancelBtn)
                    },
                )
            }
        }

        // ── Failed ────────────────────────────────────────────────────────────

        private fun buildFailed(state: FinalDeclState.Failed): VBox {
            val retryBtn = Button("Start again").apply {
                styleClass.add("secondary-action-button")
                setOnAction { apply(FinalDeclState.Idle) }
            }
            return VBox(8.0).apply {
                children.addAll(
                    wrappingLabel(state.message).apply { styleClass.add("status-error") },
                    retryBtn,
                )
            }
        }

        // ── HMRC calls ────────────────────────────────────────────────────────

        private fun triggerIntentToFinalise(taxYear: String) {
            scope.launch {
                val connection = requireConnected(userId, settingsRepository, getApiClient) { message ->
                    Platform.runLater { apply(FinalDeclState.Failed(message)); onStatusChange(message) }
                } ?: return@launch
                val (settings, client) = connection

                val calcTestScenario = if (isSandbox) Config.sandboxCalcScenario else null
                if (isSandbox && calcTestScenario != null) {
                    logger.info { "triggerIntentToFinalise: calc Gov-Test-Scenario=$calcTestScenario" }
                }

                logger.info { "Final declaration: triggering intent-to-finalise for $taxYear" }

                val calcResult = IndividualCalculationsClient(client).triggerAndFetch(
                    nino            = settings.nino,
                    taxYear         = taxYear,
                    context         = getContext(),
                    testScenario    = calcTestScenario,
                    calculationType = "intent-to-finalise",
                )

                if (calcResult is ApiResult.Failure) {
                    val msg = "Failed to obtain calculation from HMRC: ${calcResult.message}"
                    logger.error { msg }
                    Platform.runLater { apply(FinalDeclState.Failed(msg)); onStatusChange("Calculation failed") }
                    return@launch
                }

                val taxSummary = (calcResult as ApiResult.Success).data
                logger.info {
                    "intent-to-finalise received: id=${taxSummary.calculationId} taxYear=${taxSummary.taxYear}"
                }

                // triggerIntentToFinalise — UK BSAS GET
                val ukBsasTestScenario      = if (isSandbox) Config.sandboxBsasUkPropGetScenario else null

                // triggerIntentToFinalise — foreign BSAS GET
                val foreignBsasTestScenario = if (isSandbox) Config.sandboxBsasForeignPropGetScenario else null

                if (isSandbox) {
                    if (ukBsasTestScenario      != null) logger.info { "UK BSAS Gov-Test-Scenario=$ukBsasTestScenario" }
                    if (foreignBsasTestScenario != null) logger.info { "Foreign BSAS Gov-Test-Scenario=$foreignBsasTestScenario" }
                }

                logger.info { "Fetching UK + foreign BSAS: calculationId=${taxSummary.calculationId}" }

                val ukBsasResult = withContext(Dispatchers.IO) {
                    BsasClient(client).getUkPropertyBsas(
                        nino          = settings.nino,
                        calculationId = taxSummary.calculationId,
                        taxYear       = taxYear,
                        context       = getContext(),
                        testScenario  = ukBsasTestScenario,
                    )
                }

                val foreignBsasResult = withContext(Dispatchers.IO) {
                    BsasClient(client).getForeignPropertyBsas(
                        nino          = settings.nino,
                        calculationId = taxSummary.calculationId,
                        taxYear       = taxYear,
                        context       = getContext(),
                        testScenario  = foreignBsasTestScenario,
                    )
                }

                val ukBsas      = (ukBsasResult      as? ApiResult.Success)?.data
                val foreignBsas = (foreignBsasResult as? ApiResult.Success)?.data

                if (ukBsasResult      is ApiResult.Failure) logger.warn { "UK BSAS fetch failed: ${ukBsasResult.message}" }
                if (foreignBsasResult is ApiResult.Failure) logger.warn { "Foreign BSAS fetch failed: ${foreignBsasResult.message}" }

                Platform.runLater {
                    when {
                        ukBsas != null -> {
                            logger.info { "UK BSAS retrieved — showing UK BSAS review (foreignBsas=${if (foreignBsas != null) "also present" else "absent"})" }
                            apply(FinalDeclState.AwaitingBsasReview(taxSummary, ukBsas, foreignBsas))
                            onStatusChange("Review UK property adjustable summary")
                        }
                        foreignBsas != null -> {
                            logger.info { "No UK BSAS — showing foreign BSAS review directly" }
                            apply(FinalDeclState.AwaitingForeignBsasReview(taxSummary, foreignBsas))
                            onStatusChange("Review foreign property adjustable summary")
                        }
                        else -> {
                            logger.info { "No BSAS for either property type — proceeding to confirm" }
                            apply(FinalDeclState.AwaitingConfirm(taxSummary))
                            onStatusChange("Review calculation and confirm")
                        }
                    }
                }
            }
        }

        private fun submitUkBsasAdjustments(
            taxYear:       String,
            calculationId: String,
            payload:       BsasPostPayload,
            summary:       TaxCalculationSummary,
            nextState:     (TaxCalculationSummary) -> FinalDeclState,
        ) {
            scope.launch {
                val connection = requireConnected(userId, settingsRepository, getApiClient) { message ->
                    Platform.runLater { apply(FinalDeclState.Failed(message)); onStatusChange(message) }
                } ?: return@launch
                val (settings, client) = connection

                // submitUkBsasAdjustments — UK BSAS POST
                val testScenario = if (isSandbox) Config.sandboxBsasUkPropPostScenario else null

                logger.info {
                    "Submitting UK BSAS adjustments: taxYear=$taxYear calculationId=$calculationId " +
                            "payload=${if (payload is BsasPostPayload.Zero) "zero" else "full"}"
                }

                val result = withContext(Dispatchers.IO) {
                    BsasClient(client).submitUkPropertyBsas(
                        nino          = settings.nino,
                        calculationId = calculationId,
                        taxYear       = taxYear,
                        payload       = payload,
                        context       = getContext(),
                        testScenario  = testScenario,
                    )
                }

                Platform.runLater {
                    when (result) {
                        is ApiResult.Failure -> {
                            val msg = "Failed to submit UK property adjustments: ${result.message}"
                            logger.error { msg }
                            apply(FinalDeclState.Failed(msg))
                            onStatusChange("UK BSAS submission failed")
                        }
                        is ApiResult.Success -> {
                            logger.info { "UK BSAS adjustments accepted" }
                            apply(nextState(summary))
                            onStatusChange("UK adjustments submitted — continuing")
                        }
                    }
                }
            }
        }

        private fun submitForeignBsasAdjustments(
            taxYear:       String,
            calculationId: String,
            payload:       ForeignPropertyBsasPostPayload,
            summary:       TaxCalculationSummary,
        ) {
            scope.launch {
                val connection = requireConnected(userId, settingsRepository, getApiClient) { message ->
                    Platform.runLater { apply(FinalDeclState.Failed(message)); onStatusChange(message) }
                } ?: return@launch
                val (settings, client) = connection

                // submitForeignBsasAdjustments — foreign BSAS POST
                val testScenario = if (isSandbox) Config.sandboxBsasForeignPropPostScenario else null

                logger.info {
                    "Submitting foreign BSAS adjustments: taxYear=$taxYear calculationId=$calculationId " +
                            "countries=${payload.countryLevelDetail.map { it.countryCode }}"
                }

                val result = withContext(Dispatchers.IO) {
                    BsasClient(client).submitForeignPropertyBsas(
                        nino          = settings.nino,
                        calculationId = calculationId,
                        taxYear       = taxYear,
                        payload       = payload,
                        context       = getContext(),
                        testScenario  = testScenario,
                    )
                }

                Platform.runLater {
                    when (result) {
                        is ApiResult.Failure -> {
                            val msg = "Failed to submit foreign property adjustments: ${result.message}"
                            logger.error { msg }
                            apply(FinalDeclState.Failed(msg))
                            onStatusChange("Foreign BSAS submission failed")
                        }
                        is ApiResult.Success -> {
                            logger.info { "Foreign BSAS adjustments accepted — proceeding to confirm" }
                            apply(FinalDeclState.AwaitingConfirm(summary))
                            onStatusChange("Foreign adjustments submitted — review and confirm declaration")
                        }
                    }
                }
            }
        }

        private fun submitDeclaration(taxYear: String, calculationId: String) {
            scope.launch {
                val connection = requireConnected(userId, settingsRepository, getApiClient) { message ->
                    Platform.runLater { apply(FinalDeclState.Failed(message)); onStatusChange(message) }
                } ?: return@launch
                val (settings, client) = connection

                val testScenario = if (isSandbox) Config.sandboxFinalDeclScenario else null
                if (isSandbox && testScenario != null) {
                    logger.info { "submitDeclaration: Gov-Test-Scenario=$testScenario" }
                }

                val extraHeaders = if (testScenario != null) mapOf("Gov-Test-Scenario" to testScenario) else emptyMap()
                val path = "/individuals/calculations/${settings.nino}/self-assessment" +
                        "/$taxYear/$calculationId/final-declaration"

                logger.info { "Submitting final declaration: POST $path" }

                val response = withContext(Dispatchers.IO) {
                    client.post(
                        path         = path,
                        body         = "{}",
                        context      = getContext(),
                        version      = "8.0",
                        extraHeaders = extraHeaders,
                    )
                }

                Platform.runLater {
                    when {
                        response == null -> {
                            val msg = "No response received from HMRC. Check your network connection and try again."
                            logger.error { "Final declaration: null response" }
                            apply(FinalDeclState.Failed(msg))
                            onStatusChange("Final declaration — network error")
                        }
                        response.statusCode() == 204 -> {
                            logger.info { "Final declaration accepted: taxYear=$taxYear calculationId=$calculationId" }
                            scope.launch(Dispatchers.IO) {
                                SubmissionRepository.record(
                                    userId         = userId,
                                    periodId       = null,
                                    taxYear        = taxYear,
                                    submissionType = "final_declaration",
                                    hmrcResponse   = "204",
                                )
                            }
                            apply(FinalDeclState.Done)
                            onStatusChange("Final declaration submitted ✓")
                        }
                        else -> {
                            val body = response.body().trim()
                            val msg  = buildString {
                                append("HMRC returned HTTP ${response.statusCode()}.")
                                if (body.isNotEmpty()) append("\n\n$body")
                            }
                            logger.error { "Final declaration failed: HTTP ${response.statusCode()} — $body" }
                            apply(FinalDeclState.Failed(msg))
                            onStatusChange("Final declaration failed — HTTP ${response.statusCode()}")
                        }
                    }
                }
            }
        }
    }
}