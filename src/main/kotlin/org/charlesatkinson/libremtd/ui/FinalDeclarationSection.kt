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
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.charlesatkinson.libremtd.api.models.BsasPostPayload
import org.charlesatkinson.libremtd.api.models.ForeignPropertyBsasPostPayload
import org.charlesatkinson.libremtd.api.models.ForeignPropertyBsasResponse
import org.charlesatkinson.libremtd.api.models.UkPropertyBsasResponse
import org.charlesatkinson.libremtd.database.*
import org.charlesatkinson.libremtd.network.*
import org.charlesatkinson.libremtd.ui.components.*
import org.charlesatkinson.libremtd.utils.ApiResult
import org.charlesatkinson.libremtd.utils.Config

private val logger = KotlinLogging.logger {}

// ── Pop-up and notice wording ─────────────────────────────────────────────────
//
// Top-level and free of JavaFX so that the wording can be unit tested.

private const val CONTINUING_TEXT = "LibreMTD will now request the final tax calculation from HMRC."

/** Shown in place of the Make final declaration button when the year is already declared. */
internal fun alreadyDeclaredMessage(taxYear: String): String =
    "✓ A final declaration has already been made for $taxYear. It can no longer be amended."

/** Why the final declaration cannot start yet: a pop-up [title] and [message]. */
internal data class ObligationsProblem(val title: String, val message: String)

/**
 * Whether the quarterly obligations allow the final declaration to start.
 * Null if they do: there is at least one obligation and every one is
 * fulfilled.
 *
 * An empty list is a problem and not "nothing is unfulfilled". The
 * obligations table is empty while it is still loading (it is rebuilt when
 * the Submissions pane is revisited or the tax year changes), when LibreMTD
 * is not connected to HMRC, and when HMRC returned none. In none of those
 * cases has LibreMTD seen that the quarters are fulfilled, and the final
 * declaration cannot be undone.
 */
internal fun obligationsProblem(taxYear: String, obligations: List<Obligation>): ObligationsProblem? {
    if (obligations.isEmpty()) {
        return ObligationsProblem(
            title   = "Declaration Cannot Proceed – Obligations Not Loaded",
            message = "LibreMTD has no quarterly obligations for $taxYear to check. They may still be " +
                    "loading, you may not be connected to HMRC, or HMRC may have returned none.\n\n" +
                    "Wait for the Quarterly Obligations table to fill, or click its Refresh button, " +
                    "then try again. A final declaration cannot be made until LibreMTD has seen that " +
                    "every quarter is fulfilled.",
        )
    }

    val unfulfilled = obligations.filter { it.status != ObligationStatus.Fulfilled }
    if (unfulfilled.isEmpty()) return null

    return ObligationsProblem(
        title   = "Declaration Cannot Proceed – Obligations Not Fulfilled",
        message = "${unfulfilled.size} quarter(s) are not yet fulfilled:\n\n" +
                unfulfilled.joinToString("\n") { "  ${it.periodKey}" } +
                "\n\nSubmit to HMRC for each open quarter before making your final declaration.",
    )
}

/** Shown when HMRC has no adjustable summary for any type of property the user has. */
internal fun noAdjustableSummaryMessage(taxYear: String): String =
    "HMRC has no adjustable summary for $taxYear, so there was nothing to adjust.\n\n$CONTINUING_TEXT"

/** Shown when the user asked for adjustments but has no properties in LibreMTD. */
internal fun noPropertiesMessage(taxYear: String): String =
    "You have no properties in LibreMTD, so there is nothing to adjust for $taxYear.\n\n$CONTINUING_TEXT"

/**
 * Shown when HMRC's foreign property summary includes properties which no
 * property in LibreMTD has the HMRC property ID of. Null if every property is
 * recognised, so there is nothing to show.
 */
internal fun unrecognisedPropertiesMessage(blocks: List<ForeignBsasPropertyBlock>): String? {
    val unknown = blocks.filter { !it.matchedLocalProperty }
    if (unknown.isEmpty()) return null

    val single = unknown.size == 1
    val count  = countInWords(unknown.size).replaceFirstChar { it.uppercase() }
    val listed = unknown.joinToString("\n") {
        "HMRC property ID ${it.propertyId} (country ${it.countryCode ?: "not reported by HMRC"})"
    }
    return "$count ${if (single) "property" else "properties"} in HMRC's adjustable summary " +
            "${if (single) "is" else "are"} not recognised by LibreMTD:\n\n" +
            "$listed\n\n" +
            "${if (single) "It" else "They"} may have been deleted in LibreMTD, or registered with " +
            "HMRC outside LibreMTD. ${if (single) "It is" else "They are"} shown on the next screen " +
            "as \"Unrecognised property\". HMRC still expects a decision about " +
            "${if (single) "it" else "each"}, so check the figures carefully."
}

/**
 * The final declaration, in the order HMRC requires:
 *
 *   1. (only if the user wants accounting adjustments) trigger a BSAS for each
 *      type of property the user has, and retrieve it
 *   2. submit the adjustments, if any
 *   3. trigger and retrieve the intent-to-finalise tax calculation
 *   4. submit the final declaration with that calculation's id
 *
 * A BSAS has its own calculationId, from Trigger BSAS. It is a different id
 * from the one of the intent-to-finalise calculation, and the two must never
 * be mixed up. A BSAS can be adjusted only once, so every adjustment starts
 * from a freshly triggered BSAS.
 *
 * If the tax year has already been finally declared, the section shows a
 * notice instead of the Make final declaration button.
 *
 * @param onInProgressChange Called on the JavaFX thread with the tax year and
 *                           true when a declaration is started, and with false
 *                           when it returns to the starting state (Cancel, or
 *                           Start again after a failure). The host uses it to
 *                           hide the submit sections, which are no use while a
 *                           declaration is in progress. It is not called with
 *                           false after the declaration is made, nor for a
 *                           year which was already declared.
 */
class FinalDeclarationSection(
    private val scope:              CoroutineScope,
    private val userId:             Int,
    private val settingsRepository: SettingsRepository,
    private val getApiClient:       suspend () -> HmrcApiClient?,
    private val getContext:         () -> ClientContext,
    private val onStatusChange:     (String) -> Unit,
    private val getObligations:     () -> List<Obligation>,
    private val onInProgressChange: (taxYear: String, inProgress: Boolean) -> Unit = { _, _ -> },
) {
    private val isSandbox: Boolean = Config.hmrcSandbox

    fun build(taxYear: String): VBox {
        val dynamicArea = VBox(10.0).apply {
            maxWidth = Double.MAX_VALUE
        }
        StateMachine(taxYear, dynamicArea).start()

        return buildSection(
            title    = "Final Declaration – $taxYear",
            infoText = normalizeText(
                """
                    Submits your self assessment final declaration to HMRC for the $taxYear
                    tax year. All four quarterly obligations must be fulfilled before HMRC will
                    accept the declaration, and LibreMTD must have loaded them: if the
                    Quarterly Obligations table is empty, wait for it to fill or click Refresh.
                    You may want to review your figures in the Tax Summary pane before
                    proceeding.
                    {NL}{NL}
                    You are first asked whether you want to make accounting adjustments. Only
                    if you do, LibreMTD asks HMRC for an adjustable summary, which HMRC will
                    provide only after the tax year has ended.
                    {NL}{NL}
                    While a final declaration is in progress, the sections for submitting UK
                    and foreign property to HMRC are hidden. They return if you cancel. They
                    are also hidden once a final declaration has been made for the tax year,
                    because the year is then locked.
                    {NL}{NL}
                    The deadline for final declaration is 31 January following the end of the
                    tax year.
                """
            ),
            rows = listOf(dynamicArea),
        )
    }

    /** A UK property BSAS, with the calculationId which Trigger BSAS gave for it. */
    private data class UkReview(
        val bsas:          UkPropertyBsasResponse,
        val calculationId: String,
    )

    /** The foreign property BSAS, the blocks the review screen shows, and its own calculationId. */
    private data class ForeignReview(
        val bsas:          ForeignPropertyBsasResponse,
        val blocks:        List<ForeignBsasPropertyBlock>,
        val calculationId: String,
    )

    private sealed class FinalDeclState {
        /** Asking the database whether the year is already declared. Shown briefly when the section is built. */
        object Checking : FinalDeclState()
        /** The year was already finally declared: nothing can be done here. */
        object AlreadyDeclared : FinalDeclState()
        object Idle : FinalDeclState()
        object HintShown : FinalDeclState()
        object AskAdjustments : FinalDeclState()
        data class Working(val text: String) : FinalDeclState()
        data class AwaitingBsasReview(
            val uk:      UkReview,
            val foreign: ForeignReview? = null,
        ) : FinalDeclState()
        data class AwaitingForeignBsasReview(
            val foreign: ForeignReview,
            val afterUk: Boolean,
        ) : FinalDeclState()
        data class AwaitingConfirm(val summary: TaxCalculationSummary) : FinalDeclState()
        object Done : FinalDeclState()
        data class Failed(val message: String) : FinalDeclState()
    }

    private inner class StateMachine(
        private val taxYear:     String,
        private val dynamicArea: VBox,
    ) {
        private var state: FinalDeclState = FinalDeclState.Checking

        /**
         * Shows the "Checking" line, then asks the database whether the year
         * is already declared, and shows either the notice or the Make final
         * declaration button. The result is used only if the section is still
         * in the Checking state, and goes straight into [state] rather than
         * through [apply], because neither outcome is a declaration being in
         * progress.
         */
        fun start() {
            render()
            scope.launch(Dispatchers.IO) {
                val declared = try {
                    SubmissionRepository.isFinalDeclared(userId, taxYear)
                } catch (e: Exception) {
                    logger.error(e) { "Could not check whether $taxYear has been finally declared" }
                    false
                }
                withContext(Dispatchers.JavaFx) {
                    if (state is FinalDeclState.Checking) {
                        state = if (declared) FinalDeclState.AlreadyDeclared else FinalDeclState.Idle
                        render()
                    }
                }
            }
        }

        /** A state in which a declaration has been started and is not over. */
        private fun inProgress(s: FinalDeclState): Boolean =
            s !is FinalDeclState.Idle && s !is FinalDeclState.Checking && s !is FinalDeclState.AlreadyDeclared

        private fun apply(next: FinalDeclState) {
            val wasInProgress = inProgress(state)
            val isInProgress  = inProgress(next)
            state = next
            render()
            if (wasInProgress != isInProgress) onInProgressChange(taxYear, isInProgress)
        }

        private fun render() {
            dynamicArea.children.setAll(buildFor(state))
        }

        private fun buildFor(state: FinalDeclState): javafx.scene.Node = when (state) {
            is FinalDeclState.Checking                  -> hintLabel("Checking whether a final declaration has been made…")
            is FinalDeclState.AlreadyDeclared           -> wrappingLabel(alreadyDeclaredMessage(taxYear))
                .apply { styleClass.add("status-success") }
            is FinalDeclState.Idle                      -> buildIdle()
            is FinalDeclState.HintShown                 -> buildHintShown()
            is FinalDeclState.AskAdjustments            -> buildAskAdjustments()
            is FinalDeclState.Working                   -> buildBusyLabel(state.text)
            is FinalDeclState.AwaitingBsasReview        -> buildBsasReview(state)
            is FinalDeclState.AwaitingForeignBsasReview -> buildForeignBsasReview(state)
            is FinalDeclState.AwaitingConfirm           -> buildAwaitingConfirm(state)
            is FinalDeclState.Done                      -> wrappingLabel("✓ Final declaration submitted successfully.")
                .apply { styleClass.add("status-success") }
            is FinalDeclState.Failed                    -> buildFailed(state)
        }

        private fun buildBusyLabel(text: String) =
            wrappingLabel(text).apply { styleClass.add("status-warning") }

        // ── Shared widgets ────────────────────────────────────────────────────

        private fun summaryRow(label: String, value: Double?) =
            HBox(12.0).apply {
                alignment = Pos.CENTER_LEFT
                children.addAll(
                    Label(label).apply { prefWidth = 280.0 },
                    Label(if (value != null) "£%,.2f".format(value) else "–"),
                )
            }

        private fun fieldRow(label: String, field: TextField) =
            HBox(12.0).apply {
                alignment = Pos.CENTER_LEFT
                children.addAll(
                    Label(label).apply { prefWidth = 280.0 },
                    field,
                )
            }

        private fun prefilled(value: Double?) =
            TextField(formatAdjustment(value)).apply { prefWidth = 120.0 }

        private fun boldLabel(text: String, size: Int = 13) =
            wrappingLabel(text).apply { style = "-fx-font-weight: bold; -fx-font-size: ${size}px;" }

        /** The on-screen warning that a new adjustment replaces any earlier one. */
        private fun overwriteWarning() =
            wrappingLabel("A new submission replaces any earlier adjustment HMRC holds for $taxYear.").apply {
                styleClass.addAll("warning-banner", "warning-banner-text")
            }

        /**
         * Keeps the "Submit adjustments" and "No adjustments" buttons in step
         * with whether any field has been changed: once a field is changed,
         * submitting is the main action and skipping is not offered.
         */
        private fun wireButtonStyles(allFields: List<TextField>, initialValues: Map<TextField, String>,
                                     submitBtn: Button, skipBtn: Button) {
            fun update() {
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
            allFields.forEach { field -> field.textProperty().addListener { _, _, _ -> update() } }
            update()
        }

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
                        "Tax Summary pane. When you click Continue, LibreMTD checks that your " +
                        "quarterly obligations are loaded and fulfilled and asks whether you want " +
                        "to make accounting adjustments. It then requests a final tax calculation " +
                        "from HMRC and shows you the key figures for confirmation before anything " +
                        "is submitted."
            )

            val continueBtn = Button("Continue").apply {
                styleClass.add("primary-action-button")
                padding = Insets(8.0, 16.0, 8.0, 16.0)
                setOnAction {
                    // Read at the moment of the click, so it reflects the
                    // obligations table as it is now, not as it was when this
                    // screen was built.
                    val problem = obligationsProblem(taxYear, getObligations())
                    if (problem != null) {
                        Dialogs.showError(problem.message, title = problem.title)
                        return@setOnAction
                    }
                    apply(FinalDeclState.AskAdjustments)
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

        // ── Ask whether to make adjustments ───────────────────────────────────

        private fun buildAskAdjustments(): VBox {
            val question = boldLabel("Do you want to make accounting adjustments?", 14)

            val explanation = hintLabel(
                normalizeText(
                    """
                        Accounting adjustments are corrections to the property income and expenses
                        you have already submitted. Most users with straightforward property
                        business do not need to make any.
                        {NL}{NL}
                        If you choose "Yes, make adjustments", LibreMTD asks HMRC for an adjustable
                        summary of each type of property you have. HMRC will provide one only after
                        the tax year has ended and when all your quarterly obligations are
                        fulfilled. You can then review the summary and enter adjustments.
                        {NL}{NL}
                        If you choose "No adjustments", LibreMTD goes straight on to request the
                        final tax calculation.
                    """
                )
            )

            val noBtn = Button("No adjustments – continue").apply {
                styleClass.add("primary-action-button")
                padding = Insets(8.0, 16.0, 8.0, 16.0)
                setOnAction { proceedToCalculation() }
            }

            val yesBtn = Button("Yes, make adjustments").apply {
                styleClass.add("secondary-action-button")
                padding = Insets(8.0, 16.0, 8.0, 16.0)
                setOnAction {
                    apply(FinalDeclState.Working("Requesting adjustable summaries from HMRC…"))
                    startAdjustments()
                }
            }

            val cancelBtn = Button("Cancel").apply {
                styleClass.add("secondary-action-button")
                setOnAction { apply(FinalDeclState.Idle) }
            }

            return VBox(10.0).apply {
                maxWidth = Double.MAX_VALUE
                children.addAll(
                    question,
                    explanation,
                    HBox(8.0).apply {
                        alignment = Pos.CENTER_LEFT
                        children.addAll(noBtn, yesBtn, cancelBtn)
                    },
                )
            }
        }

        // ── UK property BSAS review ───────────────────────────────────────────

        private fun buildBsasReview(state: FinalDeclState.AwaitingBsasReview): VBox {
            val uk      = state.uk
            val bsas    = uk.bsas
            val calc    = bsas.adjustableSummaryCalculation
            val foreign = state.foreign

            val existingIncome   = bsas.adjustments?.income.toFieldMap()
            val existingExpenses = bsas.adjustments?.expenses.toFieldMap()

            val incomeFields: Map<String, TextField> =
                UK_INCOME_ADJUSTMENT_FIELDS.associate { it.key to prefilled(existingIncome[it.key]) }
            val expenseFields: Map<String, TextField> =
                ADJUSTMENT_EXPENSE_FIELDS.associate { it.key to prefilled(existingExpenses[it.key]) }

            val allFields: List<TextField> = incomeFields.values + expenseFields.values
            val initialValues: Map<TextField, String> = allFields.associateWith { it.text }

            val validationLabel = wrappingLabel("").apply { styleClass.add("status-error") }

            fun inputsOf(fields: Map<String, TextField>): Map<String, AdjustmentInput> =
                fields.mapValues { (_, field) -> AdjustmentInput(initialValues.getValue(field), field.text) }

            val nextHint = if (foreign != null)
                hintLabel("After UK property adjustments, you will review foreign property adjustments.")
            else
                hintLabel("After this, LibreMTD will request the final tax calculation from HMRC.")

            val submitBtn = Button("Submit adjustments").apply {
                styleClass.add("secondary-action-button")
                padding = Insets(8.0, 16.0, 8.0, 16.0)
                setOnAction {
                    when (val built = buildUkPostPayload(inputsOf(incomeFields), inputsOf(expenseFields))) {
                        is BuiltPayload.Invalid -> {
                            validationLabel.text = invalidAdjustmentMessage(built)
                        }
                        is BuiltPayload.Ready -> {
                            val payload = built.payload
                            if (payload is BsasPostPayload.Adjustments &&
                                payload.income == null && payload.expenses == null
                            ) {
                                validationLabel.text = "There is nothing to submit. Click \"No adjustments\" instead."
                                return@setOnAction
                            }
                            validationLabel.text = ""
                            apply(FinalDeclState.Working("Submitting UK property accounting adjustments to HMRC…"))
                            submitUkBsasAdjustments(uk.calculationId, payload, foreign)
                        }
                    }
                }
            }

            val skipBtn = Button("No adjustments").apply {
                styleClass.add("primary-action-button")
                padding = Insets(8.0, 16.0, 8.0, 16.0)
                setOnAction { afterUkAdjustments(foreign) }
            }

            val cancelBtn = Button("Cancel").apply {
                styleClass.add("secondary-action-button")
                setOnAction { apply(FinalDeclState.Idle) }
            }

            wireButtonStyles(allFields, initialValues, submitBtn, skipBtn)

            val stepLabel = if (foreign != null)
                "Step 1 of 2 – Review and adjust UK property figures"
            else
                "Review and adjust UK property figures"

            val net = netResult(calc?.netProfit, calc?.netLoss)
            val tax = taxResult(calc?.taxableProfit, calc?.adjustedIncomeTaxLoss)

            return VBox(10.0).apply {
                maxWidth = Double.MAX_VALUE
                children.addAll(
                    boldLabel(stepLabel, 12),
                    hintLabel(bsasHintText("UK property")),
                    overwriteWarning(),
                    Separator(),
                    boldLabel("HMRC calculated summary"),
                    summaryRow("Total income",   calc?.totalIncome),
                    summaryRow("Total expenses", calc?.totalExpenses),
                    summaryRow(net.label,        net.amount),
                    summaryRow(tax.label,        tax.amount),
                    Separator(),
                    boldLabel("Income adjustments"),
                )
                UK_INCOME_ADJUSTMENT_FIELDS.forEach { f ->
                    children.add(fieldRow(f.label, incomeFields.getValue(f.key)))
                }
                children.addAll(
                    Separator(),
                    boldLabel("Expense adjustments"),
                )
                ADJUSTMENT_EXPENSE_FIELDS.forEach { f ->
                    children.add(fieldRow(f.label, expenseFields.getValue(f.key)))
                }
                children.addAll(Separator(), validationLabel, nextHint)
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
            val foreign = state.foreign
            val calc    = foreign.bsas.adjustableSummaryCalculation

            class BlockFields(
                val block:    ForeignBsasPropertyBlock,
                val income:   Map<String, TextField>,
                val expenses: Map<String, TextField>,
            )

            val blockFields: List<BlockFields> = foreign.blocks.map { block ->
                BlockFields(
                    block,
                    FOREIGN_INCOME_ADJUSTMENT_FIELDS.associate { it.key to prefilled(block.existingIncome[it.key]) },
                    ADJUSTMENT_EXPENSE_FIELDS.associate { it.key to prefilled(block.existingExpenses[it.key]) },
                )
            }

            val allFields: List<TextField> = blockFields.flatMap { it.income.values + it.expenses.values }
            val initialValues: Map<TextField, String> = allFields.associateWith { it.text }

            val validationLabel = wrappingLabel("").apply { styleClass.add("status-error") }

            fun inputsOf(fields: Map<String, TextField>): Map<String, AdjustmentInput> =
                fields.mapValues { (_, field) -> AdjustmentInput(initialValues.getValue(field), field.text) }

            fun buildPayload(): BuiltPayload<ForeignPropertyBsasPostPayload> =
                buildForeignPostPayload(
                    blockFields.map { bf ->
                        PropertyAdjustmentInputs(bf.block.propertyId, inputsOf(bf.income), inputsOf(bf.expenses))
                    }
                )

            val submitBtn = Button("Submit adjustments").apply {
                styleClass.add("secondary-action-button")
                padding = Insets(8.0, 16.0, 8.0, 16.0)
                setOnAction {
                    when (val built = buildPayload()) {
                        is BuiltPayload.Invalid -> {
                            validationLabel.text = invalidAdjustmentMessage(built)
                        }
                        is BuiltPayload.Ready -> {
                            val payload = built.payload
                            if (payload.propertyLevelDetail.isEmpty()) {
                                proceedToCalculation()
                                return@setOnAction
                            }
                            validationLabel.text = ""
                            apply(FinalDeclState.Working("Submitting foreign property accounting adjustments to HMRC…"))
                            submitForeignBsasAdjustments(foreign.calculationId, payload)
                        }
                    }
                }
            }

            val skipBtn = Button("No adjustments").apply {
                styleClass.add("primary-action-button")
                padding = Insets(8.0, 16.0, 8.0, 16.0)
                setOnAction { proceedToCalculation() }
            }

            val cancelBtn = Button("Cancel").apply {
                styleClass.add("secondary-action-button")
                setOnAction { apply(FinalDeclState.Idle) }
            }

            wireButtonStyles(allFields, initialValues, submitBtn, skipBtn)

            val net = netResult(calc?.netProfit, calc?.netLoss)
            val tax = taxResult(calc?.taxableProfit, calc?.adjustedIncomeTaxLoss)

            val stepLabel = if (state.afterUk)
                "Step 2 of 2 – Review and adjust foreign property figures"
            else
                "Review and adjust foreign property figures"

            return VBox(10.0).apply {
                maxWidth = Double.MAX_VALUE
                children.addAll(
                    boldLabel(stepLabel, 12),
                    hintLabel(bsasHintText("foreign property")),
                    overwriteWarning(),
                    hintLabel(
                        "HMRC reports foreign property figures for each property, and they are shown " +
                                "here under their country. Adjust each property separately."
                    ),
                    Separator(),
                    boldLabel("HMRC calculated summary (all foreign property)"),
                    summaryRow("Total income",   calc?.totalIncome),
                    summaryRow("Total expenses", calc?.totalExpenses),
                    summaryRow(net.label,        net.amount),
                    summaryRow(tax.label,        tax.amount),
                )

                if (blockFields.isEmpty()) {
                    children.addAll(
                        Separator(),
                        hintLabel("HMRC reports no foreign properties for this summary, so there is nothing to adjust."),
                    )
                }

                var started = false
                var lastCountry: String? = null
                blockFields.forEach { bf ->
                    val block = bf.block
                    if (!started || block.countryCode != lastCountry) {
                        children.add(Separator())
                        children.add(boldLabel("Country: ${block.countryCode ?: "not reported by HMRC"}", 14))
                        started = true
                        lastCountry = block.countryCode
                    }

                    // A property LibreMTD does not recognise is labelled here
                    // by its HMRC property ID. The explanation is a pop-up,
                    // shown when this screen appears (see showUnrecognisedNotice).
                    children.add(boldLabel(block.label))

                    val propertyNet = netResult(block.summary?.netProfit, block.summary?.netLoss)
                    val propertyTax = taxResult(block.summary?.taxableProfit, block.summary?.adjustedIncomeTaxLoss)
                    children.addAll(
                        summaryRow("Total income",     block.summary?.totalIncome),
                        summaryRow("Total expenses",   block.summary?.totalExpenses),
                        summaryRow(propertyNet.label,  propertyNet.amount),
                        summaryRow(propertyTax.label,  propertyTax.amount),
                        wrappingLabel("Income adjustments").apply {
                            style = "-fx-font-size: 12px; -fx-font-weight: bold;"
                        },
                    )
                    FOREIGN_INCOME_ADJUSTMENT_FIELDS.forEach { f ->
                        children.add(fieldRow(f.label, bf.income.getValue(f.key)))
                    }
                    children.add(
                        wrappingLabel("Expense adjustments").apply {
                            style = "-fx-font-size: 12px; -fx-font-weight: bold;"
                        }
                    )
                    ADJUSTMENT_EXPENSE_FIELDS.forEach { f ->
                        children.add(fieldRow(f.label, bf.expenses.getValue(f.key)))
                    }
                }

                children.addAll(
                    Separator(),
                    validationLabel,
                    hintLabel("After this, LibreMTD will request the final tax calculation from HMRC."),
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
                    apply(FinalDeclState.Working("Submitting final declaration to HMRC…"))
                    submitDeclaration(s.calculationId)
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

        // ── Moving between steps (all called on the JavaFX thread) ───────────

        /** After the UK adjustments are submitted or skipped: foreign review if there is one, else the calculation. */
        private fun afterUkAdjustments(foreign: ForeignReview?) {
            if (foreign != null) {
                apply(FinalDeclState.AwaitingForeignBsasReview(foreign, afterUk = true))
                showUnrecognisedNotice(foreign)
            } else {
                proceedToCalculation()
            }
        }

        /** The adjustments step is over (or was never wanted): request the intent-to-finalise calculation. */
        private fun proceedToCalculation() {
            apply(FinalDeclState.Working("Requesting calculation from HMRC…"))
            triggerIntentToFinalise()
        }

        /**
         * A pop-up which the user must dismiss, listing any HMRC properties
         * LibreMTD does not recognise. Shown after the foreign review screen
         * is on display, so the user can see the screen it refers to. Does
         * nothing if every property is recognised.
         */
        private fun showUnrecognisedNotice(foreign: ForeignReview) {
            val message = unrecognisedPropertiesMessage(foreign.blocks) ?: return
            logger.info { "Unrecognised foreign properties in the BSAS: ${foreign.blocks.filter { !it.matchedLocalProperty }.map { it.propertyId }}" }
            Dialogs.showInfo(message, title = "Unrecognised property")
        }

        // ── HMRC calls ────────────────────────────────────────────────────────

        /**
         * Triggers and retrieves a BSAS for each type of property the user
         * has, then shows the review screen(s). Nothing is submitted here.
         * Any failure stops the flow: the final declaration cannot be undone,
         * so a summary which could not be obtained or read must not be
         * skipped unseen. If there is nothing to adjust, a pop-up says so.
         */
        private fun startAdjustments() {
            scope.launch {
                val connection = requireConnected(userId, settingsRepository, getApiClient) { message ->
                    Platform.runLater { apply(FinalDeclState.Failed(message)); onStatusChange(message) }
                } ?: return@launch
                val (settings, client) = connection

                val properties = withContext(Dispatchers.IO) { PropertyRepository.findByUser(userId) }
                val hasUk      = properties.any { it.propertyType == PropertyType.UK }
                val hasForeign = properties.any { it.propertyType == PropertyType.FOREIGN }

                if (!hasUk && !hasForeign) {
                    logger.info { "Adjustments requested but the user has no properties – going to the calculation" }
                    Platform.runLater {
                        onStatusChange("No properties, so no adjustments are possible")
                        Dialogs.showInfo(noPropertiesMessage(taxYear), title = "Nothing to adjust")
                        proceedToCalculation()
                    }
                    return@launch
                }

                val triggerScenario        = if (isSandbox) Config.sandboxBsasTriggerScenario else null
                val ukGetScenario          = if (isSandbox) Config.sandboxBsasUkPropGetScenario else null
                val foreignGetScenario     = if (isSandbox) Config.sandboxBsasForeignPropGetScenario else null
                val bsasClient             = BsasClient(client)
                val context                = getContext()

                logger.info { "Adjustments requested for $taxYear: uk=$hasUk foreign=$hasForeign" }

                // ── 1. Trigger ────────────────────────────────────────────────
                val ukTrigger: ApiResult<String>? =
                    if (hasUk) withContext(Dispatchers.IO) {
                        bsasClient.triggerBsas(
                            nino           = settings.nino,
                            businessId     = settings.businessIdUk,
                            typeOfBusiness = BSAS_TYPE_UK,
                            taxYear        = taxYear,
                            context        = context,
                            testScenario   = triggerScenario,
                        )
                    } else null

                val foreignTrigger: ApiResult<String>? =
                    if (hasForeign) withContext(Dispatchers.IO) {
                        bsasClient.triggerBsas(
                            nino           = settings.nino,
                            businessId     = settings.businessIdForeign,
                            typeOfBusiness = BSAS_TYPE_FOREIGN,
                            taxYear        = taxYear,
                            context        = context,
                            testScenario   = triggerScenario,
                        )
                    } else null

                val triggerProblems = listOfNotNull(
                    (ukTrigger as? ApiResult.Failure)?.let { "UK property: ${it.message}" },
                    (foreignTrigger as? ApiResult.Failure)?.let { "Foreign property: ${it.message}" },
                )
                if (triggerProblems.isNotEmpty()) {
                    stopWith(
                        "HMRC would not provide an adjustable summary. Nothing has been submitted.",
                        triggerProblems,
                        "Adjustable summary not available",
                    )
                    return@launch
                }

                val ukId      = (ukTrigger as? ApiResult.Success)?.data
                val foreignId = (foreignTrigger as? ApiResult.Success)?.data

                // ── 2. Retrieve ───────────────────────────────────────────────
                val ukRetrieval: BsasRetrieval<UkPropertyBsasResponse> =
                    if (ukId != null) withContext(Dispatchers.IO) {
                        bsasClient.getUkPropertyBsas(
                            nino          = settings.nino,
                            calculationId = ukId,
                            taxYear       = taxYear,
                            context       = context,
                            testScenario  = ukGetScenario,
                        )
                    } else BsasRetrieval.NotAvailable

                val foreignRetrieval: BsasRetrieval<ForeignPropertyBsasResponse> =
                    if (foreignId != null) withContext(Dispatchers.IO) {
                        bsasClient.getForeignPropertyBsas(
                            nino          = settings.nino,
                            calculationId = foreignId,
                            taxYear       = taxYear,
                            context       = context,
                            testScenario  = foreignGetScenario,
                        )
                    } else BsasRetrieval.NotAvailable

                val retrieveProblems = listOfNotNull(
                    (ukRetrieval as? BsasRetrieval.Failed)?.let { "UK property: ${it.message}" },
                    (foreignRetrieval as? BsasRetrieval.Failed)?.let { "Foreign property: ${it.message}" },
                )
                if (retrieveProblems.isNotEmpty()) {
                    stopWith(
                        "LibreMTD could not read HMRC's adjustable summary. Nothing has been submitted.",
                        retrieveProblems,
                        "Adjustable summary could not be read",
                    )
                    return@launch
                }

                val ukReview = (ukRetrieval as? BsasRetrieval.Found)?.data?.let { UkReview(it, ukId!!) }
                val foreignReview = (foreignRetrieval as? BsasRetrieval.Found)?.data?.let {
                    ForeignReview(it, buildForeignBsasBlocks(it, properties), foreignId!!)
                }

                Platform.runLater {
                    when {
                        ukReview != null -> {
                            logger.info { "UK BSAS retrieved – showing UK review (foreign ${if (foreignReview != null) "also present" else "absent"})" }
                            apply(FinalDeclState.AwaitingBsasReview(ukReview, foreignReview))
                            onStatusChange("Review UK property adjustable summary")
                        }
                        foreignReview != null -> {
                            logger.info { "No UK BSAS – showing foreign review directly" }
                            apply(FinalDeclState.AwaitingForeignBsasReview(foreignReview, afterUk = false))
                            onStatusChange("Review foreign property adjustable summary")
                            showUnrecognisedNotice(foreignReview)
                        }
                        else -> {
                            logger.info { "HMRC has no adjustable summary for either type – going to the calculation" }
                            onStatusChange("HMRC has no adjustable summary, so there is nothing to adjust")
                            Dialogs.showInfo(noAdjustableSummaryMessage(taxYear), title = "Nothing to adjust")
                            proceedToCalculation()
                        }
                    }
                }
            }
        }

        /** Shows [headline] and each of [problems] as a failure, and logs them. */
        private fun stopWith(headline: String, problems: List<String>, status: String) {
            val msg = headline + "\n\n" + problems.joinToString("\n\n") + "\n\nThe details are in the log."
            logger.error { "Final declaration stopped: $problems" }
            Platform.runLater { apply(FinalDeclState.Failed(msg)); onStatusChange(status) }
        }

        private fun submitUkBsasAdjustments(
            calculationId: String,
            payload:       BsasPostPayload,
            foreign:       ForeignReview?,
        ) {
            scope.launch {
                val connection = requireConnected(userId, settingsRepository, getApiClient) { message ->
                    Platform.runLater { apply(FinalDeclState.Failed(message)); onStatusChange(message) }
                } ?: return@launch
                val (settings, client) = connection

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
                            val msg = "Failed to submit UK property adjustments:\n\n${result.message}"
                            logger.error { msg }
                            apply(FinalDeclState.Failed(msg))
                            onStatusChange("UK BSAS submission failed")
                        }
                        is ApiResult.Success -> {
                            logger.info { "UK BSAS adjustments accepted" }
                            onStatusChange("UK adjustments submitted – continuing")
                            afterUkAdjustments(foreign)
                        }
                    }
                }
            }
        }

        private fun submitForeignBsasAdjustments(
            calculationId: String,
            payload:       ForeignPropertyBsasPostPayload,
        ) {
            scope.launch {
                val connection = requireConnected(userId, settingsRepository, getApiClient) { message ->
                    Platform.runLater { apply(FinalDeclState.Failed(message)); onStatusChange(message) }
                } ?: return@launch
                val (settings, client) = connection

                val testScenario = if (isSandbox) Config.sandboxBsasForeignPropPostScenario else null

                logger.info {
                    "Submitting foreign BSAS adjustments: taxYear=$taxYear calculationId=$calculationId " +
                            "properties=${payload.propertyLevelDetail.map { it.propertyId }}"
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
                            val msg = "Failed to submit foreign property adjustments:\n\n${result.message}"
                            logger.error { msg }
                            apply(FinalDeclState.Failed(msg))
                            onStatusChange("Foreign BSAS submission failed")
                        }
                        is ApiResult.Success -> {
                            logger.info { "Foreign BSAS adjustments accepted – requesting the calculation" }
                            onStatusChange("Foreign adjustments submitted – continuing")
                            proceedToCalculation()
                        }
                    }
                }
            }
        }

        /**
         * Triggers and retrieves the intent-to-finalise calculation. This is
         * the only calculationId the final declaration may use, and it is
         * deliberately not the id of any BSAS.
         */
        private fun triggerIntentToFinalise() {
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

                when (calcResult) {
                    is ApiResult.Failure -> {
                        val msg = "Failed to obtain calculation from HMRC: ${calcResult.message}"
                        logger.error { msg }
                        Platform.runLater { apply(FinalDeclState.Failed(msg)); onStatusChange("Calculation failed") }
                    }
                    is ApiResult.Success -> {
                        val taxSummary = calcResult.data
                        logger.info {
                            "intent-to-finalise received: id=${taxSummary.calculationId} taxYear=${taxSummary.taxYear}"
                        }
                        Platform.runLater {
                            apply(FinalDeclState.AwaitingConfirm(taxSummary))
                            onStatusChange("Review calculation and confirm")
                        }
                    }
                }
            }
        }

        /**
         * Submits the final declaration with [calculationId], which must be
         * the intent-to-finalise calculation's. The submission is recorded in
         * the database before the screen says Done, because that record is
         * what locks the tax year against further changes.
         */
        private fun submitDeclaration(calculationId: String) {
            scope.launch {
                val connection = requireConnected(userId, settingsRepository, getApiClient) { message ->
                    Platform.runLater { apply(FinalDeclState.Failed(message)); onStatusChange(message) }
                } ?: return@launch
                val (settings, client) = connection

                val testScenario = if (isSandbox) Config.sandboxFinalDeclScenario else null
                if (isSandbox && testScenario != null) {
                    logger.info { "submitDeclaration: Gov-Test-Scenario=$testScenario" }
                }

                val result = withContext(Dispatchers.IO) {
                    IndividualCalculationsClient(client).submitFinalDeclaration(
                        nino          = settings.nino,
                        taxYear       = taxYear,
                        calculationId = calculationId,
                        context       = getContext(),
                        testScenario  = testScenario,
                    )
                }

                when (result) {
                    is ApiResult.Failure -> {
                        logger.error { "Final declaration failed: ${result.message}" }
                        Platform.runLater {
                            apply(FinalDeclState.Failed(result.message))
                            onStatusChange("Final declaration failed")
                        }
                    }
                    is ApiResult.Success -> {
                        logger.info { "Final declaration accepted: taxYear=$taxYear calculationId=$calculationId" }
                        try {
                            withContext(Dispatchers.IO) {
                                SubmissionRepository.record(
                                    userId         = userId,
                                    periodId       = null,
                                    taxYear        = taxYear,
                                    submissionType = "final_declaration",
                                    hmrcResponse   = "204",
                                )
                            }
                            Platform.runLater {
                                apply(FinalDeclState.Done)
                                onStatusChange("Final declaration submitted ✓")
                            }
                        } catch (e: Exception) {
                            logger.error(e) { "Final declaration accepted by HMRC but could not be recorded" }
                            Platform.runLater {
                                apply(
                                    FinalDeclState.Failed(
                                        "HMRC accepted the final declaration, but LibreMTD could not record " +
                                                "that in its database: ${e.message}\n\nDo not submit it again. " +
                                                "The details are in the log."
                                    )
                                )
                                onStatusChange("Final declaration accepted, but not recorded locally")
                            }
                        }
                    }
                }
            }
        }
    }
}
