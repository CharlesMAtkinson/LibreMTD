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
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.charlesatkinson.libremtd.database.ExpenseRepository
import org.charlesatkinson.libremtd.database.IncomePropertyRepository
import org.charlesatkinson.libremtd.database.PeriodRepository
import org.charlesatkinson.libremtd.database.PropertyRepository
import org.charlesatkinson.libremtd.database.SettingsRepository
import org.charlesatkinson.libremtd.database.SubmissionRepository
import org.charlesatkinson.libremtd.network.*
import org.charlesatkinson.libremtd.security.TokenStore
import org.charlesatkinson.libremtd.ui.components.Dialogs
import org.charlesatkinson.libremtd.ui.components.TaxYearSelector
import org.charlesatkinson.libremtd.utils.ApiResult
import org.charlesatkinson.libremtd.utils.Config
import org.charlesatkinson.libremtd.ui.components.infoPopup
import org.charlesatkinson.libremtd.ui.components.normalizeText
import org.charlesatkinson.libremtd.ui.components.wrappingLabel
import java.time.LocalDate

private val logger = KotlinLogging.logger {}

// ── Status label helpers ──────────────────────────────────────────────────────

private val STATUS_CLASSES = listOf("status-success", "status-error", "status-warning", "hint-label")

private fun Label.setStatusStyle(styleClass: String) {
    this.styleClass.removeAll(STATUS_CLASSES)
    this.styleClass.add(styleClass)
}

// ── Tax year date helpers ─────────────────────────────────────────────────────

/** Returns the tax year start date, e.g. "2025-26" → "2025-04-06". */
private fun taxYearStart(taxYear: String): String =
    "${taxYear.take(4)}-04-06"

/**
 * Returns the end date of the latest quarter whose end date is on or before
 * today, or null if the first quarter has not yet ended.
 *
 * Standard UK tax year quarters (6-Apr start):
 *   Q1  6 Apr – 5 Jul    due 7 Aug
 *   Q2  6 Jul – 5 Oct    due 7 Nov
 *   Q3  6 Oct – 5 Jan    due 7 Feb
 *   Q4  6 Jan – 5 Apr    due 7 May
 */
private fun latestEndedQuarterDate(taxYear: String): String? {
    val startYear = taxYear.take(4).toInt()
    val quarters = listOf(
        "$startYear-07-05",
        "$startYear-10-05",
        "${startYear + 1}-01-05",
        "${startYear + 1}-04-05",
    )
    val today = LocalDate.now()
    return quarters.lastOrNull { LocalDate.parse(it) <= today }
}

// ── Final declaration UI state ────────────────────────────────────────────────

private sealed class FinalDeclState {
    object Idle : FinalDeclState()
    object HintShown : FinalDeclState()
    object Triggering : FinalDeclState()
    data class AwaitingConfirm(val summary: TaxCalculationSummary) : FinalDeclState()
    object Submitting : FinalDeclState()
    object Done : FinalDeclState()
    data class Failed(val message: String) : FinalDeclState()
}

// ─────────────────────────────────────────────────────────────────────────────

class SubmissionsPane(
    private val scope:              CoroutineScope,
    private val userId:             Int,
    private val settingsRepository: SettingsRepository,
    private val getApiClient:       suspend () -> HmrcApiClient?,
    private val getContext:         () -> ClientContext,
    private val onStatusChange:     (String) -> Unit,
) {

    /**
     * Wrapper placed in the pane cache by MainWindow.
     * Holds a back-reference to the SubmissionsPane so that MainWindow can call
     * refresh() without needing to cast to an internal layout type.
     */
    class RefreshableRoot(vbox: VBox, val refreshablePane: SubmissionsPane) : VBox() {
        init {
            children.add(vbox)
            // Inherit sizing so the inner VBox fills the scroll area normally.
            VBox.setVgrow(vbox, javafx.scene.layout.Priority.ALWAYS)
        }
    }

    /** The node placed in the scene graph and stored in MainWindow's pane cache. */
    val root: RefreshableRoot

    private val isSandbox: Boolean = Config.hmrcSandbox

    private lateinit var selectedYear:      String
    private lateinit var sectionsBox:       VBox
    private lateinit var obligationsTable:  TableView<Obligation>
    private lateinit var obligationsStatus: Label

    private val taxYearSelector = TaxYearSelector(userId = userId) { year ->
        selectedYear = year
        onStatusChange("Tax year: $year")
        if (::sectionsBox.isInitialized) {
            refreshSections()
            autoRefreshObligations()
        }
    }

    init {
        val innerVBox = buildUI()
        root = RefreshableRoot(innerVBox, this)
    }

    /**
     * Called by MainWindow after a successful HMRC connection event.
     * Re-fetches obligations so the table is populated without the user
     * having to navigate away and back.
     * Must be called on the JavaFX application thread.
     */
    fun refresh() {
        logger.info { "SubmissionsPane.refresh() called — reloading obligations" }
        autoRefreshObligations()
    }

    private fun buildUI(): VBox {
        sectionsBox = VBox(20.0)
        refreshSections()

        // TaxYearSelector fires its callback during construction, before sectionsBox
        // exists, so autoRefreshObligations is suppressed at that point.
        // Call it explicitly now that everything is initialised.
        autoRefreshObligations()

        return VBox(20.0).apply {
            padding = Insets(4.0)
            children.addAll(
                wrappingLabel("Submissions").apply {
                    style = "-fx-font-size: 22px; -fx-font-weight: bold;"
                },
                taxYearSelector.root,
                sectionsBox,
            )
        }
    }

    private fun refreshSections() {
        sectionsBox.children.setAll(
            buildObligationsSection(),
            buildSubmitSection(),
            buildFinalDeclarationSection(),
        )
    }

    // ── Obligations ───────────────────────────────────────────────────────────

    private fun buildObligationsSection(): VBox {
        obligationsTable = TableView<Obligation>().apply {
            prefHeight  = 220.0
            placeholder = wrappingLabel("Connect to HMRC to load obligations")
            columns.addAll(
                TableColumn<Obligation, String>("Period").apply {
                    prefWidth = 240.0
                    setCellValueFactory { it.value.periodKey.toObservable() }
                },
                TableColumn<Obligation, String>("Start").apply {
                    prefWidth = 100.0
                    setCellValueFactory { it.value.start.toObservable() }
                },
                TableColumn<Obligation, String>("End").apply {
                    prefWidth = 100.0
                    setCellValueFactory { it.value.end.toObservable() }
                },
                TableColumn<Obligation, String>("Due").apply {
                    prefWidth = 100.0
                    setCellValueFactory { it.value.due.toObservable() }
                },
                TableColumn<Obligation, String>("Status").apply {
                    prefWidth = 90.0
                    setCellValueFactory { it.value.status.name.toObservable() }
                },
            )
        }

        obligationsStatus = wrappingLabel("").apply {
            styleClass.add("hint-label")
        }

        val refreshBtn = Button("Refresh").apply {
            styleClass.add("secondary-action-button")
            setOnAction { autoRefreshObligations() }
        }

        return buildSection(
            title    = "Quarterly Obligations — $selectedYear",
            infoText = "HMRC's record of which quarterly submissions it has received for this tax year. " +
                    "All four quarters must show Fulfilled before you can make a final declaration. " +
                    "This table refreshes automatically when you change tax year or submit.",
            rows     = listOf(
                HBox(8.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(refreshBtn, obligationsStatus)
                },
                obligationsTable,
            ),
        )
    }

    private fun autoRefreshObligations() {
        if (TokenStore.isExpired() || TokenStore.getAccessToken() == null) {
            logger.info { "autoRefreshObligations: skipped — not connected" }
            return
        }

        val taxYear      = selectedYear
        val testScenario = if (isSandbox) Config.sandboxObligationsScenario else null

        if (isSandbox && testScenario != null) {
            logger.info { "autoRefreshObligations: using Gov-Test-Scenario=$testScenario" }
        }

        scope.launch {
            val settings = withContext(Dispatchers.IO) { settingsRepository.load(userId) }
            val nino = settings?.nino?.takeIf { it.isNotBlank() } ?: run {
                logger.warn { "autoRefreshObligations: skipped — NINO not set" }
                return@launch
            }
            val client = getApiClient() ?: run {
                logger.warn { "autoRefreshObligations: skipped — API client returned null" }
                return@launch
            }

            val fromDate = taxYearStart(taxYear)
            val toDate   = "20${taxYear.takeLast(2)}-04-05"

            logger.info { "autoRefreshObligations: fetching for $taxYear ($fromDate to $toDate)" }

            val result = ObligationsClient(client).fetchObligations(
                nino         = nino,
                fromDate     = fromDate,
                toDate       = toDate,
                context      = getContext(),
                testScenario = testScenario,
            )

            Platform.runLater {
                when (result) {
                    is ApiResult.Failure -> {
                        obligationsStatus.text = "Could not load obligations: ${result.message}"
                        obligationsStatus.setStatusStyle("status-error")
                    }
                    is ApiResult.Success -> {
                        val obligations = result.data
                        obligationsTable.items.setAll(obligations)
                        val fulfilled = obligations.count { it.status == ObligationStatus.Fulfilled }
                        obligationsStatus.text = "$fulfilled of ${obligations.size} quarters fulfilled"
                        obligationsStatus.setStatusStyle(
                            if (fulfilled == obligations.size) "status-success" else "hint-label"
                        )
                        scope.launch(Dispatchers.IO) {
                            val properties = PropertyRepository.findByUser(userId)
                            if (properties.isNotEmpty()) {
                                obligations.forEach { obligation ->
                                    PeriodRepository.upsert(
                                        taxYear   = taxYear,
                                        periodKey = obligation.periodKey,
                                        startDate = obligation.start,
                                        endDate   = obligation.end,
                                        dueDate   = obligation.due,
                                    )
                                }
                                logger.info { "Persisted ${obligations.size} periods for $taxYear" }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Submit to HMRC ────────────────────────────────────────────────────────

    private fun buildSubmitSection(): VBox {
        val taxYear = selectedYear

        val statusLabel = wrappingLabel("").apply {
            styleClass.add("hint-label")
        }

        val submitBtn = Button("Submit to HMRC").apply {
            styleClass.add("primary-action-button")
            padding = Insets(8.0, 16.0, 8.0, 16.0)
            setOnAction {
                isDisable        = true
                statusLabel.text = "Preparing…"
                statusLabel.setStatusStyle("status-warning")
                handleCumulativeSubmit(this, statusLabel, taxYear)
            }
        }

                return buildSection(
            title    = "Submit to HMRC — $taxYear",
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
            rows     = listOf(
                HBox(12.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(submitBtn, statusLabel)
                },
            ),
        )
    }

    private fun handleCumulativeSubmit(
        submitBtn:   Button,
        statusLabel: Label,
        taxYear:     String,
    ) {
        scope.launch {
            val settings = withContext(Dispatchers.IO) { settingsRepository.load(userId) }

            if (settings == null || settings.nino.isBlank()) {
                Platform.runLater {
                    submitBtn.isDisable = false
                    statusLabel.text    = "NINO not set — go to Settings"
                    statusLabel.setStatusStyle("status-error")
                    onStatusChange("NINO not configured")
                }
                return@launch
            }

            if (TokenStore.isExpired() || TokenStore.getAccessToken() == null) {
                Platform.runLater {
                    submitBtn.isDisable = false
                    statusLabel.text    = "Not connected to HMRC"
                    statusLabel.setStatusStyle("status-error")
                    onStatusChange("Not connected to HMRC")
                }
                return@launch
            }

            if (settings.businessId.isBlank()) {
                Platform.runLater {
                    submitBtn.isDisable = false
                    statusLabel.text    = "Business ID not set — go to Settings"
                    statusLabel.setStatusStyle("status-error")
                    onStatusChange("Business ID not configured")
                }
                return@launch
            }

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

            val client = getApiClient() ?: run {
                Platform.runLater {
                    submitBtn.isDisable = false
                    statusLabel.text    = "Client not configured — check Settings"
                    statusLabel.setStatusStyle("status-error")
                    onStatusChange("API client not configured")
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

            val result = PropertySubmissionClient(client).submitCumulative(
                nino       = settings.nino,
                businessId = settings.businessId,
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
                        submissionType = "cumulative",
                        hmrcResponse   = result.statusCode.toString(),
                    )
                }
                logger.info { "Submission succeeded — calling autoRefreshObligations" }
                autoRefreshObligations()
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

    // ── Aggregate helpers ─────────────────────────────────────────────────────

    private suspend fun aggregateIncome(taxYear: String): UkPropertyIncomeBody? =
        withContext(Dispatchers.IO) {
            val allEntries = IncomePropertyRepository.currentPropertyIncomeForYear(userId, taxYear)
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
            val allEntries = ExpenseRepository.currentPropertyExpensesForYear(userId, taxYear)
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

    // ── Final declaration ─────────────────────────────────────────────────────

    private fun buildFinalDeclarationSection(): VBox {
        val taxYear = selectedYear

        val dynamicArea = VBox(10.0)

        fun applyState(state: FinalDeclState) {
            dynamicArea.children.setAll(
                when (state) {

                    is FinalDeclState.Idle -> {
                        val declareBtn = Button("Make final declaration").apply {
                            styleClass.add("primary-action-button")
                            padding = Insets(8.0, 16.0, 8.0, 16.0)
                            setOnAction { applyState(FinalDeclState.HintShown) }
                        }
                        HBox(declareBtn).apply { alignment = Pos.CENTER_LEFT }
                    }

                    is FinalDeclState.HintShown -> {
                        val hint = wrappingLabel(
                            "Before continuing, you may want to review your figures in the " +
                                    "Tax Summary pane. When you click Continue, LibreMTD will request " +
                                    "a final tax calculation from HMRC and show you the key figures " +
                                    "for confirmation before anything is submitted."
                        ).apply { styleClass.add("hint-label") }

                        val continueBtn = Button("Continue").apply {
                            styleClass.add("primary-action-button")
                            padding = Insets(8.0, 16.0, 8.0, 16.0)
                            setOnAction {
                                val obligations = obligationsTable.items.toList()
                                val unfulfilled = obligations.filter { it.status != ObligationStatus.Fulfilled }
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
                                applyState(FinalDeclState.Triggering)
                                triggerIntentToFinalise(taxYear, ::applyState)
                            }
                        }

                        val cancelBtn = Button("Cancel").apply {
                            styleClass.add("secondary-action-button")
                            setOnAction { applyState(FinalDeclState.Idle) }
                        }

                        VBox(10.0).apply {
                            children.addAll(
                                hint,
                                HBox(8.0).apply {
                                    alignment = Pos.CENTER_LEFT
                                    children.addAll(continueBtn, cancelBtn)
                                },
                            )
                        }
                    }

                    is FinalDeclState.Triggering -> {
                        wrappingLabel("Requesting calculation from HMRC…").apply {
                            styleClass.add("status-warning")
                        }
                    }

                    is FinalDeclState.AwaitingConfirm -> {
                        val s = state.summary

                        fun row(label: String, value: Double) =
                            HBox(12.0).apply {
                                alignment = Pos.CENTER_LEFT
                                children.addAll(
                                    Label(label).apply { prefWidth = 260.0 },
                                    Label("£%,.2f".format(value)),
                                )
                            }

                        val summaryBox = VBox(6.0).apply {
                            padding = Insets(8.0, 0.0, 8.0, 0.0)
                            children.addAll(
                                wrappingLabel("Calculation summary (intent to finalise)").apply {
                                    style = "-fx-font-weight: bold;"
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

                        val warning = wrappingLabel(
                            "Review the figures above. Click \"Confirm and submit\" to send your " +
                                    "final declaration to HMRC. This action cannot be undone."
                        ).apply { styleClass.add("hint-label") }

                        val confirmBtn = Button("Confirm and submit").apply {
                            styleClass.add("primary-action-button")
                            padding = Insets(8.0, 16.0, 8.0, 16.0)
                            setOnAction {
                                applyState(FinalDeclState.Submitting)
                                submitDeclaration(taxYear, s.calculationId, ::applyState)
                            }
                        }

                        val cancelBtn = Button("Cancel").apply {
                            styleClass.add("secondary-action-button")
                            setOnAction { applyState(FinalDeclState.Idle) }
                        }

                        VBox(10.0).apply {
                            children.addAll(
                                summaryBox,
                                warning,
                                HBox(8.0).apply {
                                    alignment = Pos.CENTER_LEFT
                                    children.addAll(confirmBtn, cancelBtn)
                                },
                            )
                        }
                    }

                    is FinalDeclState.Submitting -> {
                        wrappingLabel("Submitting final declaration to HMRC…").apply {
                            styleClass.add("status-warning")
                        }
                    }

                    is FinalDeclState.Done -> {
                        wrappingLabel("✓ Final declaration submitted successfully.").apply {
                            styleClass.add("status-success")
                        }
                    }

                    is FinalDeclState.Failed -> {
                        val errorLabel = wrappingLabel(state.message).apply {
                            styleClass.add("status-error")
                        }
                        val retryBtn = Button("Start again").apply {
                            styleClass.add("secondary-action-button")
                            setOnAction { applyState(FinalDeclState.Idle) }
                        }
                        VBox(8.0).apply {
                            children.addAll(errorLabel, retryBtn)
                        }
                    }
                }
            )
        }

        applyState(FinalDeclState.Idle)

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
            rows     = listOf(dynamicArea),
        )
    }

    private fun triggerIntentToFinalise(
        taxYear:    String,
        applyState: (FinalDeclState) -> Unit,
    ) {
        scope.launch {
            val settings = withContext(Dispatchers.IO) { settingsRepository.load(userId) }

            if (settings == null || settings.nino.isBlank()) {
                Platform.runLater {
                    applyState(FinalDeclState.Failed("NINO not set — go to Settings."))
                    onStatusChange("NINO not configured")
                }
                return@launch
            }

            if (TokenStore.isExpired() || TokenStore.getAccessToken() == null) {
                Platform.runLater {
                    applyState(FinalDeclState.Failed("Not connected to HMRC."))
                    onStatusChange("Not connected to HMRC")
                }
                return@launch
            }

            val client = getApiClient() ?: run {
                Platform.runLater {
                    applyState(FinalDeclState.Failed("API client not configured — check Settings."))
                    onStatusChange("API client not configured")
                }
                return@launch
            }

            val testScenario = if (isSandbox) Config.sandboxCalcScenario else null
            if (isSandbox && testScenario != null) {
                logger.info { "triggerIntentToFinalise: using Gov-Test-Scenario=$testScenario" }
            }

            logger.info { "Final declaration: triggering intent-to-finalise for $taxYear" }

            val result = IndividualCalculationsClient(client).triggerAndFetch(
                nino            = settings.nino,
                taxYear         = taxYear,
                context         = getContext(),
                testScenario    = testScenario,
                calculationType = "intent-to-finalise",
            )

            Platform.runLater {
                when (result) {
                    is ApiResult.Failure -> {
                        val msg = "Failed to obtain calculation from HMRC: ${result.message}"
                        logger.error { msg }
                        applyState(FinalDeclState.Failed(msg))
                        onStatusChange("Calculation failed")
                    }
                    is ApiResult.Success -> {
                        logger.info {
                            "intent-to-finalise calculation received: " +
                                    "id=${result.data.calculationId} taxYear=${result.data.taxYear}"
                        }
                        applyState(FinalDeclState.AwaitingConfirm(result.data))
                        onStatusChange("Review calculation and confirm")
                    }
                }
            }
        }
    }

    private fun submitDeclaration(
        taxYear:       String,
        calculationId: String,
        applyState:    (FinalDeclState) -> Unit,
    ) {
        scope.launch {
            val settings = withContext(Dispatchers.IO) { settingsRepository.load(userId) }

            if (settings == null || settings.nino.isBlank()) {
                Platform.runLater {
                    applyState(FinalDeclState.Failed("NINO not set — go to Settings."))
                    onStatusChange("NINO not configured")
                }
                return@launch
            }

            if (TokenStore.isExpired() || TokenStore.getAccessToken() == null) {
                Platform.runLater {
                    applyState(FinalDeclState.Failed("Not connected to HMRC."))
                    onStatusChange("Not connected to HMRC")
                }
                return@launch
            }

            val client = getApiClient() ?: run {
                Platform.runLater {
                    applyState(FinalDeclState.Failed("API client not configured — check Settings."))
                    onStatusChange("API client not configured")
                }
                return@launch
            }

            val testScenario = if (isSandbox) Config.sandboxFinalDeclScenario else null
            if (isSandbox && testScenario != null) {
                logger.info { "submitDeclaration: using Gov-Test-Scenario=$testScenario" }
            }

            val extraHeaders = if (testScenario != null)
                mapOf("Gov-Test-Scenario" to testScenario)
            else
                emptyMap()

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
                        applyState(FinalDeclState.Failed(msg))
                        onStatusChange("Final declaration — network error")
                    }
                    response.statusCode() == 204 -> {
                        logger.info { "Final declaration accepted: taxYear=$taxYear calculationId=$calculationId" }
                        scope.launch(Dispatchers.IO) {
                            SubmissionRepository.record(
                                userId         = userId,
                                periodId       = null,
                                submissionType = "final_declaration",
                                hmrcResponse   = "204",
                            )
                        }
                        applyState(FinalDeclState.Done)
                        onStatusChange("Final declaration submitted ✓")
                    }
                    else -> {
                        val body = response.body().trim()
                        val msg = buildString {
                            append("HMRC returned HTTP ${response.statusCode()}.")
                            if (body.isNotEmpty()) append("\n\n$body")
                        }
                        logger.error { "Final declaration failed: HTTP ${response.statusCode()} — $body" }
                        applyState(FinalDeclState.Failed(msg))
                        onStatusChange("Final declaration failed — HTTP ${response.statusCode()}")
                    }
                }
            }
        }
    }

    // ── Shared builder ────────────────────────────────────────────────────────

    private fun buildSection(
        title:    String,
        infoText: String,
        rows:     List<javafx.scene.Node>,
    ): VBox {
        return VBox(8.0).apply {
            padding = Insets(12.0, 16.0, 12.0, 16.0)
            styleClass.add("content-card")
            style   = "-fx-border-radius: 8; -fx-background-radius: 8;"
            children.addAll(
                HBox(6.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(
                        wrappingLabel(title).apply {
                            style = "-fx-font-size: 15px; -fx-font-weight: bold;"
                        },
                        infoPopup(infoText),
                    )
                },
                Separator(),
                *rows.toTypedArray(),
            )
        }
    }
}

private fun String.toObservable(): javafx.beans.value.ObservableValue<String> =
    javafx.beans.property.SimpleStringProperty(this)