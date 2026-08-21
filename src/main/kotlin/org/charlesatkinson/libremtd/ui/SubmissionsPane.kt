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
import javafx.scene.layout.VBox
import kotlinx.coroutines.CoroutineScope
import mu.KotlinLogging
import org.charlesatkinson.libremtd.database.SettingsRepository
import org.charlesatkinson.libremtd.network.ClientContext
import org.charlesatkinson.libremtd.network.HmrcApiClient
import org.charlesatkinson.libremtd.ui.components.TaxYearSelector
import org.charlesatkinson.libremtd.ui.components.wrappingLabel

private val logger = KotlinLogging.logger {}

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
            VBox.setVgrow(vbox, javafx.scene.layout.Priority.ALWAYS)
        }
    }

    val root: RefreshableRoot

    private lateinit var selectedYear: String
    private lateinit var sectionsBox:  VBox

    private val obligationsSection = ObligationsSection(
        scope, userId, settingsRepository, getApiClient, getContext,
    )
    private val submitSection = CumulativeSubmitSection(
        scope, userId, settingsRepository, getApiClient, getContext, onStatusChange,
        onSubmitted = { taxYear ->
            logger.info { "Submission succeeded — refreshing obligations" }
            obligationsSection.refresh(taxYear)
        },
    )
    private val foreignSubmitSection = ForeignCumulativeSubmitSection(
        scope, userId, settingsRepository, getApiClient, getContext, onStatusChange,
        onSubmitted = { taxYear ->
            logger.info { "Foreign submission succeeded — refreshing obligations" }
            obligationsSection.refresh(taxYear)
        },
    )
    private val finalDeclarationSection = FinalDeclarationSection(
        scope, userId, settingsRepository, getApiClient, getContext, onStatusChange,
        getObligations = { obligationsSection.currentObligations() },
    )

    private val taxYearSelector = TaxYearSelector(userId = userId) { year ->
        selectedYear = year
        onStatusChange("Tax year: $year")
        if (::sectionsBox.isInitialized) {
            refreshSections()
            obligationsSection.refresh(year)
        }
    }

    init {
        val innerVBox = buildUI()
        root = RefreshableRoot(innerVBox, this)
    }

    /**
     * Called by MainWindow after a successful HMRC connection event.
     * Must be called on the JavaFX application thread.
     */
    fun refresh() {
        logger.info { "SubmissionsPane.refresh() called — reloading obligations" }
        obligationsSection.refresh(selectedYear)
    }

    private fun buildUI(): VBox {
        sectionsBox = VBox(20.0).apply {
            maxWidth = Double.MAX_VALUE
        }
        refreshSections()

        // TaxYearSelector fires its callback during construction, before sectionsBox
        // exists, so the refresh above is suppressed at that point.
        // Call it explicitly now that everything is initialised.
        obligationsSection.refresh(selectedYear)

        return VBox(20.0).apply {
            maxWidth = Double.MAX_VALUE
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
            obligationsSection.build(selectedYear),
            submitSection.build(selectedYear),
            foreignSubmitSection.build(selectedYear),
            finalDeclarationSection.build(selectedYear),
        )
    }
}
