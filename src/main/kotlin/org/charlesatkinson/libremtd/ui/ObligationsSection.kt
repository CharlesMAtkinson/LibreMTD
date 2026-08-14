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
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.charlesatkinson.libremtd.database.*
import org.charlesatkinson.libremtd.network.*
import org.charlesatkinson.libremtd.security.TokenStore
import org.charlesatkinson.libremtd.ui.components.*
import org.charlesatkinson.libremtd.utils.ApiResult
import org.charlesatkinson.libremtd.utils.Config

private val logger = KotlinLogging.logger {}

class ObligationsSection(
    private val scope:              CoroutineScope,
    private val userId:             Int,
    private val settingsRepository: SettingsRepository,
    private val getApiClient:       suspend () -> HmrcApiClient?,
    private val getContext:         () -> ClientContext,
) {
    private val isSandbox: Boolean = Config.hmrcSandbox

    lateinit var table:  TableView<Obligation>
        private set
    private lateinit var status: Label

    /** Read-only snapshot for other sections (e.g. final declaration). */
    fun currentObligations(): List<Obligation> =
        if (::table.isInitialized) table.items.toList() else emptyList()

    fun build(taxYear: String): VBox {
        table = TableView<Obligation>().apply {
            prefHeight = 160.0
            minHeight  = 160.0
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

        status = wrappingLabel("").apply { styleClass.add("hint-label") }

        val refreshBtn = Button("Refresh").apply {
            styleClass.add("secondary-action-button")
            setOnAction { refresh(taxYear) }
        }

        return buildSection(
            title    = "Quarterly Obligations — $taxYear",
            infoText = "HMRC's record of which quarterly submissions it has received for this tax year. " +
                    "All four quarters must show Fulfilled before you can make a final declaration. " +
                    "This table refreshes automatically when you change tax year or submit.",
            rows = listOf(
                HBox(8.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(refreshBtn, status)
                },
                table,
            ),
        ).also {
            VBox.setVgrow(it, javafx.scene.layout.Priority.NEVER)
        }
    }

    fun refresh(taxYear: String) {
        if (TokenStore.isExpired() || TokenStore.getAccessToken() == null) {
            logger.info { "ObligationsSection.refresh: skipped — not connected" }
            return
        }

        val testScenario = if (isSandbox) Config.sandboxObligationsScenario else null
        if (isSandbox && testScenario != null) {
            logger.info { "ObligationsSection.refresh: using Gov-Test-Scenario=$testScenario" }
        }

        scope.launch {
            val settings = withContext(Dispatchers.IO) { settingsRepository.load(userId) }
            val nino = settings?.nino?.takeIf { it.isNotBlank() } ?: run {
                logger.warn { "ObligationsSection.refresh: skipped — NINO not set" }
                return@launch
            }
            val client = getApiClient() ?: run {
                logger.warn { "ObligationsSection.refresh: skipped — API client returned null" }
                return@launch
            }

            val fromDate = taxYearStart(taxYear)
            val toDate   = taxYearEnd(taxYear)

            logger.info { "ObligationsSection.refresh: fetching for $taxYear ($fromDate to $toDate)" }

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
                        status.text = "Could not load obligations: ${result.message}"
                        status.setStatusStyle("status-error")
                    }
                    is ApiResult.Success -> {
                        val obligations = result.data
                        table.items.setAll(obligations)
                        val fulfilled = obligations.count { it.status == ObligationStatus.Fulfilled }
                        status.text = "$fulfilled of ${obligations.size} quarters fulfilled"
                        status.setStatusStyle(
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
}