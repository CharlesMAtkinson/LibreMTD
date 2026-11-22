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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.charlesatkinson.libremtd.database.SettingsRepository
import org.charlesatkinson.libremtd.database.SubmissionRepository
import org.charlesatkinson.libremtd.network.ClientContext
import org.charlesatkinson.libremtd.network.HmrcApiClient
import org.charlesatkinson.libremtd.ui.components.RefreshableRoot
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

    val root: RefreshableRoot

    private lateinit var selectedYear: String
    private lateinit var sectionsBox:  VBox

    // The two submit sections, kept so they can be hidden. They are of no
    // use while a final declaration is in progress, nor once the year has
    // been finally declared (it is then locked). Rebuilt by refreshSections().
    private lateinit var ukSubmitNode:      VBox
    private lateinit var foreignSubmitNode: VBox

    // True from the start of a final declaration until it is cancelled. Reset
    // when the sections are rebuilt, because the new final declaration
    // section starts idle. Read on the JavaFX thread only.
    private var declarationInProgress = false

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
        // A declaration which belongs to a tax year no longer selected is
        // ignored: refreshSections() has already replaced its sections.
        onInProgressChange = { taxYear, inProgress ->
            if (taxYear == selectedYear) {
                declarationInProgress = inProgress
                updateSubmitSectionsVisibility()
            }
        },
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
        root = RefreshableRoot(innerVBox) { refresh() }
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

    /**
     * Shows or hides the UK and foreign submit sections. Hidden sections
     * take no layout space.
     */
    private fun setSubmitSectionsVisible(visible: Boolean) {
        if (!::ukSubmitNode.isInitialized || !::foreignSubmitNode.isInitialized) return
        listOf(ukSubmitNode, foreignSubmitNode).forEach {
            it.isVisible = visible
            it.isManaged = visible
        }
    }

    /**
     * Hides the submit sections while a final declaration is in progress, and
     * for any year which has been finally declared. Otherwise shows them.
     *
     * While a declaration is in progress they are hidden at once. Otherwise
     * the database is asked, on a background thread, whether the year is
     * finally declared. A result is dropped if, by the time it arrives, a
     * declaration has started or another tax year has been selected: a
     * stale answer must not show sections which should be hidden.
     */
    private fun updateSubmitSectionsVisibility() {
        if (declarationInProgress) {
            setSubmitSectionsVisible(false)
            return
        }

        val year = selectedYear
        scope.launch(Dispatchers.IO) {
            val locked = SubmissionRepository.isFinalDeclared(userId, year)
            withContext(Dispatchers.JavaFx) {
                if (year == selectedYear && !declarationInProgress) {
                    setSubmitSectionsVisible(!locked)
                }
            }
        }
    }

    private fun refreshSections() {
        // The final declaration section is rebuilt here too, and starts idle.
        declarationInProgress = false

        ukSubmitNode      = submitSection.build(selectedYear)
        foreignSubmitNode = foreignSubmitSection.build(selectedYear)

        sectionsBox.children.setAll(
            obligationsSection.build(selectedYear),
            ukSubmitNode,
            foreignSubmitNode,
            finalDeclarationSection.build(selectedYear),
        )

        // The new sections start visible. Hide them again at once if the
        // year has already been finally declared.
        updateSubmitSectionsVisibility()
    }
}
