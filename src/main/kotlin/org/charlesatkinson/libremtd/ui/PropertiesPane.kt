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
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.collections.FXCollections
import javafx.beans.property.SimpleStringProperty
import javafx.util.StringConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.charlesatkinson.libremtd.database.ForeignPropertyElection
import org.charlesatkinson.libremtd.database.ForeignPropertyElectionRepository
import org.charlesatkinson.libremtd.database.Property
import org.charlesatkinson.libremtd.database.PropertyRepository
import org.charlesatkinson.libremtd.database.PropertyType
import org.charlesatkinson.libremtd.database.SettingsRepository
import org.charlesatkinson.libremtd.database.SubmissionRepository
import org.charlesatkinson.libremtd.database.availableTaxYears
import org.charlesatkinson.libremtd.network.ClientContext
import org.charlesatkinson.libremtd.network.ForeignPropertyClient
import org.charlesatkinson.libremtd.network.ForeignPropertyEndReason
import org.charlesatkinson.libremtd.network.HmrcApiClient
import org.charlesatkinson.libremtd.security.FraudPreventionHeaders
import org.charlesatkinson.libremtd.security.OAuth2Handler
import org.charlesatkinson.libremtd.security.TokenStore
import org.charlesatkinson.libremtd.ui.components.Dialogs
import org.charlesatkinson.libremtd.ui.components.Dialogs.applyAppIcons
import org.charlesatkinson.libremtd.ui.components.TaxYearSelector
import org.charlesatkinson.libremtd.ui.components.attachCopyContextMenu
import org.charlesatkinson.libremtd.ui.components.hintLabel
import org.charlesatkinson.libremtd.ui.components.infoPopup
import org.charlesatkinson.libremtd.ui.components.wrappingLabel
import org.charlesatkinson.libremtd.utils.ApiResult
import org.charlesatkinson.libremtd.utils.Config

private val UK_POSTCODE_REGEX = Regex("^[A-Z]{1,2}[0-9][0-9A-Z]? ?[0-9][A-Z]{2}$")
private val ISO_ALPHA3_REGEX  = Regex("^[A-Z]{3}$")

private const val FTCR_EXPLANATION =
    "Foreign Tax Credit Relief (FTCR) lets you offset foreign tax you've already paid " +
            "on this property's income against your UK tax on the same income, so you're not " +
            "taxed twice on it. Whether claiming FTCR is the right choice for you, and how much " +
            "relief you'd be entitled to, depends on the tax you paid abroad and any double " +
            "taxation agreement between the UK and the property's country — there's no simple " +
            "yes/no default answer. See HMRC's helpsheet HS263 to work it out.\n\n" +
            "You can change this at any time before your Final Declaration for the tax year — " +
            "each quarterly submission replaces the previous one, so an earlier choice isn't locked in."

/**
 * The HMRC "Create Foreign Property Details" endpoint (which issues the UUID
 * propertyID) is only available for tax years starting from 2026-27 onwards:
 * https://developer.service.hmrc.gov.uk/api-documentation/docs/api/service/property-business-api/6.0/oas/page#tag/Foreign-Property-Details
 * For 2025-26 and earlier there is no HMRC-side record to create, so foreign
 * properties for those years are stored locally only, with no HMRC
 * connection required.
 *
 * Assumes taxYear is formatted "YYYY-YY", e.g. "2025-26" (the same format
 * TaxYearSelector uses elsewhere in the app). Adjust this if that ever
 * changes.
 */
private fun foreignPropertyRequiresHmrc(taxYear: String): Boolean {
    val startYear = taxYear.substringBefore("-").trim().toIntOrNull()
    // If the format is ever unrecognised, fail safe by treating it as
    // requiring HMRC rather than silently skipping registration.
    return startYear == null || startYear >= 2026
}

private data class ForeignPropertyEndDetails(
    val endDate: String,
    val endReason: ForeignPropertyEndReason,
)

class PropertiesPane(
    private val scope: CoroutineScope,
    private val userId: Int,
    private val settingsRepository: SettingsRepository,
    private val onStatusChange: (String) -> Unit,
) {

    val root: VBox

    private val ukProperties      = FXCollections.observableArrayList<Property>()
    private val foreignProperties = FXCollections.observableArrayList<Property>()

    // propertyId -> summary string for the FTCR table column, e.g.
    // "2025-26 ✓ · 2026-27 ✗". Recomputed in loadProperties(); read (not
    // queried) by the table's cell value factory so the FX thread never
    // makes a DB call directly.
    private val ftcrSummaries = mutableMapOf<Int, String>()

    // Shared
    private val addressField = TextField()

    // UK-only
    private val postcodeField = TextField()

    // Foreign-only
    private val countryCodeField = TextField()
    private var selectedTaxYear: String? = null
    private lateinit var taxYearSelector: TaxYearSelector
    private lateinit var foreignHintLabel: Label
    private val ftcrCheckBox = CheckBox("Claim Foreign Tax Credit Relief")

    private val typeGroup    = ToggleGroup()
    private val ukRadio      = RadioButton("UK").apply { toggleGroup = typeGroup; isSelected = true }
    private val foreignRadio = RadioButton("Foreign").apply { toggleGroup = typeGroup }

    private lateinit var addressLabel: Label
    private lateinit var postcodeRow: HBox
    private lateinit var countryRow: HBox
    private lateinit var ftcrRow: HBox
    private lateinit var addBtn: Button
    private lateinit var registerHmrcBtn: Button
    private lateinit var ftcrBtn: Button

    init {
        root = buildUI()
        loadProperties()
    }

    private fun buildUI(): VBox {
        return VBox(16.0).apply {
            padding = Insets(4.0)
            children.addAll(
                wrappingLabel("Properties").apply {
                    style = "-fx-font-size: 22px; -fx-font-weight: bold;"
                },
                wrappingLabel(
                    "Manage your let properties. Income and expenses are recorded per property " +
                            "and aggregated across all properties when submitting to HMRC."
                ).apply {
                    styleClass.add("hint-label")
                },
                buildEntryForm(),
                buildUkPropertiesTable(),
                buildForeignPropertiesTable(),
            )
        }
    }

    private fun buildEntryForm(): VBox {
        addressField.apply {
            promptText = "Address"
            maxWidth   = Double.MAX_VALUE
        }
        HBox.setHgrow(addressField, Priority.ALWAYS)

        postcodeField.apply {
            promptText = "Postcode"
            prefWidth  = 120.0
            minWidth   = 120.0
            maxWidth   = 120.0
        }

        countryCodeField.apply {
            promptText = "e.g. FRA"
            prefWidth  = 120.0
            minWidth   = 120.0
            maxWidth   = 120.0
        }

        foreignHintLabel = hintLabel("")

        // TaxYearSelector renders its own "Tax year:" label internally — do
        // not wrap it in a second one (see SubmissionsPane for the same
        // usage pattern).
        taxYearSelector = TaxYearSelector(userId = userId) { year ->
            selectedTaxYear = year
            updateForeignHint(year)
        }

        addBtn = Button("Add property").apply {
            styleClass.add("primary-action-button")
            setOnAction { handleAdd() }
        }

        val typeLabel = Label("Property type:").apply { minWidth = Region.USE_PREF_SIZE }
        addressLabel = Label("Address:").apply { minWidth = Region.USE_PREF_SIZE }
        val postcodeLabel = Label("Postcode:").apply { minWidth = Region.USE_PREF_SIZE }
        val countryLabel  = Label("Country code:").apply { minWidth = Region.USE_PREF_SIZE }

        postcodeRow = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(postcodeLabel, postcodeField)
        }
        countryRow = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(countryLabel, countryCodeField)
        }
        ftcrRow = HBox(6.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(ftcrCheckBox, infoPopup(FTCR_EXPLANATION))
        }

        // Initial state: only the type choice is visible until a type is picked.
        addressField.isVisible = false; addressField.isManaged = false
        addressLabel.isVisible = false; addressLabel.isManaged = false
        postcodeRow.isVisible = false; postcodeRow.isManaged = false
        countryRow.isVisible = false; countryRow.isManaged = false
        ftcrRow.isVisible = false; ftcrRow.isManaged = false
        taxYearSelector.root.isVisible = false; taxYearSelector.root.isManaged = false
        foreignHintLabel.isVisible = false; foreignHintLabel.isManaged = false
        addBtn.isVisible = false; addBtn.isManaged = false

        typeGroup.selectedToggleProperty().addListener { _, _, _ -> handleTypeSelected() }

        return VBox(8.0).apply {
            padding = Insets(12.0, 16.0, 12.0, 16.0)
            styleClass.add("content-card")
            style   = "-fx-border-radius: 8; -fx-background-radius: 8;"
            children.addAll(
                wrappingLabel("New property").apply { style = "-fx-font-weight: bold;" },
                Separator(),
                HBox(10.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(typeLabel, ukRadio, foreignRadio)
                },
                HBox(10.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(addressLabel, addressField)
                },
                postcodeRow,
                countryRow,
                taxYearSelector.root,
                ftcrRow,
                foreignHintLabel,
                HBox(addBtn),
            )
        }
    }

    /**
     * Foreign property entry no longer needs an HMRC connection just to be
     * *shown* — only 2026-27+ submissions need one, and we don't know the
     * chosen tax year until the user has picked it. So selecting either
     * radio just reveals the relevant fields; any HMRC connection check
     * happens later, in addForeignPropertyViaHmrc(), conditional on the tax
     * year.
     */
    private fun handleTypeSelected() {
        if (ukRadio.isSelected) revealUkFields() else revealForeignFields()
    }

    private fun revealUkFields() {
        addressLabel.text = "Address:"
        addressField.isVisible = true; addressField.isManaged = true
        addressLabel.isVisible = true; addressLabel.isManaged = true
        postcodeRow.isVisible = true; postcodeRow.isManaged = true
        countryRow.isVisible = false; countryRow.isManaged = false
        ftcrRow.isVisible = false; ftcrRow.isManaged = false
        taxYearSelector.root.isVisible = false; taxYearSelector.root.isManaged = false
        foreignHintLabel.isVisible = false; foreignHintLabel.isManaged = false
        addBtn.isVisible = true; addBtn.isManaged = true
    }

    private fun revealForeignFields() {
        addressLabel.text = "Address:"
        addressField.isVisible = true; addressField.isManaged = true
        addressLabel.isVisible = true; addressLabel.isManaged = true
        postcodeRow.isVisible = false; postcodeRow.isManaged = false
        countryRow.isVisible = true; countryRow.isManaged = true
        ftcrRow.isVisible = true; ftcrRow.isManaged = true
        taxYearSelector.root.isVisible = true; taxYearSelector.root.isManaged = true
        foreignHintLabel.isVisible = true; foreignHintLabel.isManaged = true
        addBtn.isVisible = true; addBtn.isManaged = true
        updateForeignHint(selectedTaxYear)
    }

    private fun updateForeignHint(taxYear: String?) {
        if (!::foreignHintLabel.isInitialized) return
        foreignHintLabel.text = when {
            taxYear.isNullOrBlank() -> ""
            foreignPropertyRequiresHmrc(taxYear) ->
                "For $taxYear this property will be registered with HMRC when you click " +
                        "\"Add property\", and assigned an HMRC property ID which will appear in the table below."
            else ->
                "For $taxYear, HMRC's foreign property registration isn't available. " +
                        "This property will be set up in LibreMTD only."
        }
    }

    // ── Add ─────────────────────────────────────────────────────────────

    private fun handleAdd() {
        val address = addressField.text.trim()

        if (ukRadio.isSelected) {
            handleAddUk(address)
        } else {
            handleAddForeign(address)
        }
    }

    private fun handleAddUk(address: String) {
        val postcode = postcodeField.text.trim().uppercase()
        when {
            address.isBlank() -> Dialogs.showError("Please enter an address.")
            postcode.isBlank() -> Dialogs.showError("Please enter a postcode.")
            !UK_POSTCODE_REGEX.matches(postcode) ->
                Dialogs.showError("Please enter a valid UK postcode (e.g. SW1A 1AA).")
            else -> {
                scope.launch(Dispatchers.IO) {
                    val property = PropertyRepository.create(
                        userId = userId, address = address,
                        propertyType = PropertyType.UK, postcode = postcode,
                    )
                    withContext(Dispatchers.JavaFx) {
                        ukProperties.add(property)
                        clearForm()
                        onStatusChange("Property added ✓")
                    }
                }
            }
        }
    }

    private fun handleAddForeign(address: String) {
        val country = countryCodeField.text.trim().uppercase()
        val taxYear = selectedTaxYear
        val ftcr    = ftcrCheckBox.isSelected

        when {
            address.isBlank() -> Dialogs.showError("Please enter an address.")
            country.isBlank() -> Dialogs.showError("Please enter a country code.")
            !ISO_ALPHA3_REGEX.matches(country) ->
                Dialogs.showError("Please enter a valid three-letter country code (e.g. FRA).")
            taxYear.isNullOrBlank() -> Dialogs.showError("Please select a tax year.")
            foreignPropertyRequiresHmrc(taxYear) -> addForeignPropertyViaHmrc(address, country, taxYear, ftcr)
            else -> addForeignPropertyLocalOnly(address, country, taxYear, ftcr)
        }
    }

    /** 2025-26 and earlier: no HMRC-side record exists for this endpoint, so
     *  just store the property locally. No connection check is needed. */
    private fun addForeignPropertyLocalOnly(address: String, country: String, taxYear: String, ftcr: Boolean) {
        addBtn.isDisable = true
        scope.launch(Dispatchers.IO) {
            val property = PropertyRepository.create(
                userId = userId, address = address,
                propertyType = PropertyType.FOREIGN, countryCode = country,
            )
            ForeignPropertyElectionRepository.set(property.id, taxYear, ftcr)
            withContext(Dispatchers.JavaFx) {
                addBtn.isDisable = false
                loadProperties()
                clearForm()
                onStatusChange("Foreign property added ✓")
            }
        }
    }

    /** 2026-27 onwards: HMRC issues a UUID propertyID for the foreign
     *  property, so a live connection and business ID are required. */
    private fun addForeignPropertyViaHmrc(address: String, country: String, taxYear: String, ftcr: Boolean) {
        addBtn.isDisable = true
        scope.launch(Dispatchers.IO) {
            val settings = requireConnectedSettingsOrShowError()
            if (settings == null) {
                withContext(Dispatchers.JavaFx) { addBtn.isDisable = false }
                return@launch
            }

            val prefs = org.charlesatkinson.libremtd.ui.components.UiPreferences(userId)
            val client = HmrcApiClient(
                libreMtdUserId = userId,
                isSandbox      = Config.hmrcSandbox,
                oauth2Handler  = OAuth2Handler(
                    clientId     = settings.clientId,
                    clientSecret = settings.clientSecret,
                    isSandbox    = Config.hmrcSandbox,
                    prefs        = prefs,
                ),
                fraudHeaders   = FraudPreventionHeaders(),
            )

            val result = ForeignPropertyClient(client).create(
                nino         = settings.nino,
                businessId   = settings.businessIdForeign,
                taxYear      = taxYear,
                propertyName = address,
                countryCode  = country,
                context      = ClientContext(800, 600),
                testScenario = if (Config.hmrcSandbox) "STATEFUL" else null,
            )

            when (result) {
                is ApiResult.Success -> {
                    val property = PropertyRepository.create(
                        userId = userId, address = address,
                        propertyType = PropertyType.FOREIGN, countryCode = country,
                    )
                    PropertyRepository.registerWithHmrc(property.id, result.data, taxYear)
                    ForeignPropertyElectionRepository.set(property.id, taxYear, ftcr)
                    withContext(Dispatchers.JavaFx) {
                        addBtn.isDisable = false
                        loadProperties()
                        clearForm()
                        onStatusChange("Foreign property created and added ✓")
                    }
                }
                is ApiResult.Failure -> {
                    withContext(Dispatchers.JavaFx) {
                        addBtn.isDisable = false
                        Dialogs.showError(result.message)
                    }
                }
            }
        }
    }

    // ── Register an existing local-only foreign property with HMRC ────────

    private fun handleRegisterWithHmrc(selected: Property) {
        registerHmrcBtn.isDisable = true
        scope.launch(Dispatchers.IO) {
            val settings = requireConnectedSettingsOrShowError()
            if (settings == null) {
                withContext(Dispatchers.JavaFx) { registerHmrcBtn.isDisable = false }
                return@launch
            }

            val taxYear = withContext(Dispatchers.JavaFx) { promptForRegistrationTaxYear() }
            if (taxYear == null) {
                withContext(Dispatchers.JavaFx) { registerHmrcBtn.isDisable = false }
                return@launch
            }

            val prefs = org.charlesatkinson.libremtd.ui.components.UiPreferences(userId)
            val client = HmrcApiClient(
                libreMtdUserId = userId,
                isSandbox      = Config.hmrcSandbox,
                oauth2Handler  = OAuth2Handler(
                    clientId     = settings.clientId,
                    clientSecret = settings.clientSecret,
                    isSandbox    = Config.hmrcSandbox,
                    prefs        = prefs,
                ),
                fraudHeaders   = FraudPreventionHeaders(),
            )

            val result = ForeignPropertyClient(client).create(
                nino         = settings.nino,
                businessId   = settings.businessIdForeign,
                taxYear      = taxYear,
                propertyName = selected.address,
                countryCode  = selected.countryCode ?: "",
                context      = ClientContext(800, 600),
                testScenario = if (Config.hmrcSandbox) "STATEFUL" else null,
            )

            when (result) {
                is ApiResult.Success -> {
                    PropertyRepository.registerWithHmrc(selected.id, result.data, taxYear)
                    withContext(Dispatchers.JavaFx) {
                        registerHmrcBtn.isDisable = false
                        loadProperties()
                        onStatusChange("Property registered with HMRC ✓")
                    }
                }
                is ApiResult.Failure -> {
                    withContext(Dispatchers.JavaFx) {
                        registerHmrcBtn.isDisable = false
                        Dialogs.showError(result.message)
                    }
                }
            }
        }
    }

    private fun promptForRegistrationTaxYear(): String? {
        val eligibleYears = availableTaxYears().filter { foreignPropertyRequiresHmrc(it) }
        if (eligibleYears.isEmpty()) {
            Dialogs.showError(
                "None of the available tax years support foreign property registration with " +
                        "HMRC yet (this becomes available from 2026-27 onwards).",
                title = "Not available",
            )
            return null
        }

        val combo = ComboBox<String>().apply {
            items.setAll(eligibleYears)
            value = eligibleYears.first()
        }

        val dialog = Dialog<String?>().apply {
            title = "Register with HMRC"
            headerText = "Which tax year should this property be registered under?"
            dialogPane.content = HBox(10.0, Label("Tax year:"), combo).apply {
                alignment = Pos.CENTER_LEFT
                padding = Insets(12.0)
            }
            dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
            setResultConverter { button -> if (button == ButtonType.OK) combo.value else null }
        }
        dialog.applyAppIcons()

        return dialog.showAndWait().orElse(null)
    }

    // ── Foreign Tax Credit Relief ───────────────────────────────────────

    /**
     * Tax years for which FTCR is still changeable for this property: every
     * app-supported tax year, excluding any year the user has already Final
     * Declared (once declared, that year's FTCR position is locked in and
     * no longer editable here).
     *
     * Deliberately NOT bounded by when the property was registered with
     * HMRC or added locally: hmrcRegisteredTaxYear records when HMRC
     * registration happened, not when the letting started — and 2025-26 has
     * no HMRC registration endpoint at all, so a property first let in
     * 2025-26 but only registered with HMRC once 2026-27 became available
     * still needs FTCR trackable for 2025-26. The trade-off is that a
     * property will also show FTCR options for years before it existed at
     * all; harmless, since there's simply nothing to submit for those years.
     */
    private fun openTaxYearsFor(property: Property): List<String> {
        return availableTaxYears()
            .filterNot { SubmissionRepository.isFinalDeclared(userId, it) }
            .sorted()
    }

    private fun ftcrSummaryFor(property: Property): String {
        val years = openTaxYearsFor(property)
        if (years.isEmpty()) return "—"
        return years.joinToString(" · ") { year ->
            val election = ForeignPropertyElectionRepository.current(property.id, year)
            val mark = when (election?.foreignTaxCreditRelief) {
                true  -> "✓"
                false -> "✗"
                null  -> "?"
            }
            "$year $mark"
        }
    }

    private fun handleForeignTaxCreditRelief(selected: Property) {
        val years = openTaxYearsFor(selected)
        if (years.isEmpty()) {
            Dialogs.showError(
                "There are no open tax years for this property — either none are recorded yet, " +
                        "or every year this property applies to has already had a Final Declaration made.",
                title = "No open tax years",
            )
            return
        }

        val currentByYear = years.associateWith { ForeignPropertyElectionRepository.current(selected.id, it) }
        val checkBoxByYear = mutableMapOf<String, CheckBox>()

        val grid = GridPane().apply {
            hgap = 10.0; vgap = 10.0
            padding = Insets(4.0)

            addRow(
                0,
                Label("Tax year").apply { style = "-fx-font-weight: bold;" },
                Label("Claim FTCR").apply { style = "-fx-font-weight: bold;" },
                Label(""),
            )

            years.forEachIndexed { index, year ->
                val existing = currentByYear[year]
                val checkBox = CheckBox().apply {
                    if (existing == null) {
                        isIndeterminate = true
                    } else {
                        isSelected = existing.foreignTaxCreditRelief
                    }
                }
                checkBoxByYear[year] = checkBox

                val historyText = buildString {
                    val history = ForeignPropertyElectionRepository.historyFor(selected.id, year)
                    if (history.isEmpty()) {
                        append("No FTCR decision recorded yet for $year.")
                    } else {
                        append("FTCR setting history for $year:\n")
                        history.forEach { e ->
                            val value = if (e.foreignTaxCreditRelief) "Claiming" else "Not claiming"
                            val how = if (e.rolledForwardFromTaxYear != null)
                                "carried forward from ${e.rolledForwardFromTaxYear}"
                            else "set by you"
                            append("${e.recordedAt.take(19)} — $value ($how)\n")
                        }
                    }
                }

                addRow(index + 1, Label(year), checkBox, infoPopup(historyText))
            }
        }

        val dialog = Dialog<Boolean>().apply {
            title = "Foreign Tax Credit Relief — ${selected.address}"
            headerText = null
            dialogPane.content = VBox(10.0).apply {
                children.addAll(
                    HBox(6.0, wrappingLabel("What is this?"), infoPopup(FTCR_EXPLANATION)).apply {
                        alignment = Pos.CENTER_LEFT
                    },
                    grid,
                )
            }
            dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
            setResultConverter { button -> button == ButtonType.OK }
        }
        dialog.applyAppIcons()

        val saved = dialog.showAndWait().orElse(false)
        if (!saved) return

        scope.launch(Dispatchers.IO) {
            years.forEach { year ->
                val checkBox = checkBoxByYear.getValue(year)
                if (checkBox.isIndeterminate) return@forEach // user didn't touch it — leave unset

                val existing = currentByYear[year]
                if (existing == null || existing.foreignTaxCreditRelief != checkBox.isSelected) {
                    ForeignPropertyElectionRepository.set(selected.id, year, checkBox.isSelected)
                }
            }
            withContext(Dispatchers.JavaFx) {
                loadProperties()
                onStatusChange("Foreign Tax Credit Relief updated ✓")
            }
        }
    }

    // ── Shared HMRC connection check ───────────────────────────────────────

    private suspend fun requireConnectedSettingsOrShowError() =
        settingsRepository.load(userId).let { settings ->
            when {
                settings == null || settings.businessIdForeign.isBlank() || settings.nino.isBlank() -> {
                    withContext(Dispatchers.JavaFx) {
                        Dialogs.showError(
                            "Not connected to HMRC.\n\nGo to Settings and fetch your foreign " +
                                    "property business ID first.",
                            title = "Not connected",
                        )
                    }
                    null
                }
                TokenStore.isExpired() || TokenStore.getAccessToken() == null -> {
                    withContext(Dispatchers.JavaFx) {
                        Dialogs.showError(
                            "Not connected to HMRC.\n\nConnect via HMRC Connect first.",
                            title = "Not connected",
                        )
                    }
                    null
                }
                else -> settings
            }
        }

    // ── Tables ──────────────────────────────────────────────────────────

    /** Plain centre-aligned TableCell — used where a column's values read
     *  better centred than left-aligned (e.g. postcodes, dates, codes). */
    private fun centeredCell(): TableCell<Property, String> {
        return object : TableCell<Property, String>() {
            override fun updateItem(item: String?, empty: Boolean) {
                super.updateItem(item, empty)
                text = if (empty) null else item
                alignment = Pos.CENTER
            }
        }
    }

    /** TableCell with the standard "right-click > Copy" context menu, same
     *  as used elsewhere in the app via ReadOnlyLabels.attachCopyContextMenu.
     *  Set [centered] for columns that also want centre alignment. */
    private fun copyableCell(centered: Boolean = false): TableCell<Property, String> {
        return object : TableCell<Property, String>() {
            init {
                attachCopyContextMenu(this)
                if (centered) alignment = Pos.CENTER
            }
            override fun updateItem(item: String?, empty: Boolean) {
                super.updateItem(item, empty)
                text = if (empty) null else item
            }
        }
    }

    private fun buildUkPropertiesTable(): VBox {
        val table = TableView<Property>(ukProperties).apply {
            prefHeight  = 220.0
            placeholder = wrappingLabel("No UK properties added yet")
            columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
            columns.addAll(
                TableColumn<Property, String>("Address").apply {
                    setCellValueFactory { SimpleStringProperty(it.value.address) }
                    setCellFactory { copyableCell() }
                },
                TableColumn<Property, String>("Postcode").apply {
                    prefWidth = 120.0; maxWidth = 140.0; minWidth = 100.0
                    setCellValueFactory { SimpleStringProperty(it.value.postcode ?: "") }
                    setCellFactory { copyableCell(centered = true) }
                },
                TableColumn<Property, String>("Added").apply {
                    prefWidth = 120.0; maxWidth = 140.0; minWidth = 100.0
                    setCellValueFactory { SimpleStringProperty(it.value.createdAt.take(10)) }
                    setCellFactory { centeredCell() }
                },
            )
        }

        val deleteBtn = buildDeleteButton(table) { loadProperties() }

        return VBox(8.0).apply {
            padding = Insets(12.0, 16.0, 12.0, 16.0)
            styleClass.add("content-card")
            style   = "-fx-border-radius: 8; -fx-background-radius: 8;"
            children.addAll(
                wrappingLabel("Your UK properties").apply { style = "-fx-font-weight: bold;" },
                Separator(),
                table,
                deleteBtn,
            )
        }
    }

    private fun buildForeignPropertiesTable(): VBox {
        val table = TableView<Property>(foreignProperties).apply {
            prefHeight  = 220.0
            placeholder = wrappingLabel("No foreign properties added yet")
            columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
            columns.addAll(
                TableColumn<Property, String>("Address").apply {
                    setCellValueFactory { SimpleStringProperty(it.value.address) }
                    setCellFactory { copyableCell() }
                },
                TableColumn<Property, String>("Country").apply {
                    prefWidth = 90.0; maxWidth = 100.0; minWidth = 80.0
                    setCellValueFactory { SimpleStringProperty(it.value.countryCode ?: "") }
                    setCellFactory { copyableCell(centered = true) }
                },
                TableColumn<Property, String>("Added").apply {
                    prefWidth = 120.0; maxWidth = 140.0; minWidth = 100.0
                    setCellValueFactory { SimpleStringProperty(it.value.createdAt.take(10)) }
                    setCellFactory { centeredCell() }
                },
                TableColumn<Property, String>("HMRC property ID").apply {
                    // A UUID is 36 characters — the previous 160/200/140
                    // width caused truncation (observed: cut off after 21
                    // chars). Widened to comfortably fit the full value.
                    prefWidth = 280.0; maxWidth = 320.0; minWidth = 260.0
                    setCellValueFactory { SimpleStringProperty(it.value.hmrcPropertyId ?: "") }
                    setCellFactory { copyableCell(centered = true) }
                },
                TableColumn<Property, String>("Added to HMRC").apply {
                    prefWidth = 120.0; maxWidth = 140.0; minWidth = 100.0
                    setCellValueFactory { SimpleStringProperty(it.value.hmrcRegisteredAt?.take(10) ?: "") }
                    setCellFactory { centeredCell() }
                },
                TableColumn<Property, String>("FTCR").apply {
                    prefWidth = 160.0; maxWidth = 220.0; minWidth = 120.0
                    setCellValueFactory { SimpleStringProperty(ftcrSummaries[it.value.id] ?: "…") }
                    setCellFactory { centeredCell() }
                },
            )
        }

        registerHmrcBtn = Button("Register with HMRC").apply {
            styleClass.add("primary-action-button")
            isDisable = true
            setOnAction {
                val selected = table.selectionModel.selectedItem
                when {
                    selected == null -> Dialogs.showError("Please select a property to register.")
                    selected.hmrcPropertyId != null -> Dialogs.showError("This property is already registered with HMRC.")
                    else -> handleRegisterWithHmrc(selected)
                }
            }
        }

        ftcrBtn = Button("Foreign Tax Credit Relief…").apply {
            styleClass.add("primary-action-button")
            isDisable = true
            setOnAction {
                val selected = table.selectionModel.selectedItem
                if (selected == null) {
                    Dialogs.showError("Please select a property.")
                } else {
                    handleForeignTaxCreditRelief(selected)
                }
            }
        }

        table.selectionModel.selectedItemProperty().addListener { _, _, selected ->
            registerHmrcBtn.isDisable = selected == null || selected.hmrcPropertyId != null
            ftcrBtn.isDisable = selected == null
        }

        val deleteBtn = buildDeleteButton(table) { loadProperties() }

        return VBox(8.0).apply {
            padding = Insets(12.0, 16.0, 12.0, 16.0)
            styleClass.add("content-card")
            style   = "-fx-border-radius: 8; -fx-background-radius: 8;"
            children.addAll(
                wrappingLabel("Your foreign properties").apply { style = "-fx-font-weight: bold;" },
                Separator(),
                table,
                HBox(10.0, registerHmrcBtn, ftcrBtn, deleteBtn),
            )
        }
    }

    // ── Delete ──────────────────────────────────────────────────────────

    private fun buildDeleteButton(table: TableView<Property>, onDeleted: () -> Unit): Button {
        return Button("Delete selected").apply {
            styleClass.add("primary-action-button")
            setOnAction {
                val selected = table.selectionModel.selectedItem
                if (selected == null) {
                    Dialogs.showError("Please select a property to delete.")
                    return@setOnAction
                }

                if (selected.propertyType == PropertyType.FOREIGN && selected.hmrcPropertyId != null) {
                    handleDeleteRegisteredForeignProperty(selected, onDeleted)
                } else {
                    confirmAndSoftDelete(selected, onDeleted)
                }
            }
        }
    }

    private fun confirmAndSoftDelete(selected: Property, onDeleted: () -> Unit) {
        val confirmed = Alert(Alert.AlertType.CONFIRMATION).apply {
            title       = "Delete property"
            headerText  = "Delete ${selected.address}?"
            contentText = "This will mark the property as deleted. Existing income " +
                    "and expense entries for this property are retained."
        }.showAndWait().map { it.buttonData == ButtonBar.ButtonData.OK_DONE }.orElse(false)

        if (!confirmed) return

        scope.launch(Dispatchers.IO) {
            PropertyRepository.softDelete(selected.id)
            withContext(Dispatchers.JavaFx) {
                onDeleted()
                onStatusChange("Property deleted")
            }
        }
    }

    /**
     * Deleting a foreign property that's registered with HMRC must first end
     * it there via Update Foreign Property Details (there's no separate
     * delete endpoint) — otherwise the record stays "active" on HMRC's side
     * while LibreMTD thinks it's gone. If the HMRC call fails, we deliberately
     * do NOT soft-delete locally, to keep the two in sync.
     */
    private fun handleDeleteRegisteredForeignProperty(selected: Property, onDeleted: () -> Unit) {
        val taxYear = selected.hmrcRegisteredTaxYear
        if (taxYear.isNullOrBlank()) {
            Dialogs.showError(
                "This property has an HMRC property ID but no recorded registration tax year " +
                        "— likely registered before this tracking was added. It can't be safely " +
                        "ended with HMRC automatically. Please check its record on HMRC directly, " +
                        "or if you know the tax year it was registered under, that can be backfilled " +
                        "in the database.",
                title = "Missing registration tax year",
            )
            return
        }

        scope.launch(Dispatchers.IO) {
            val settings = requireConnectedSettingsOrShowError() ?: return@launch

            val endDetails = withContext(Dispatchers.JavaFx) { promptForEndDetails(selected.address) }
            if (endDetails == null) return@launch

            val prefs = org.charlesatkinson.libremtd.ui.components.UiPreferences(userId)
            val client = HmrcApiClient(
                libreMtdUserId = userId,
                isSandbox      = Config.hmrcSandbox,
                oauth2Handler  = OAuth2Handler(
                    clientId     = settings.clientId,
                    clientSecret = settings.clientSecret,
                    isSandbox    = Config.hmrcSandbox,
                    prefs        = prefs,
                ),
                fraudHeaders   = FraudPreventionHeaders(),
            )

            val result = ForeignPropertyClient(client).end(
                nino         = settings.nino,
                propertyId   = selected.hmrcPropertyId!!,
                taxYear      = taxYear,
                propertyName = selected.address,
                endDate      = endDetails.endDate,
                endReason    = endDetails.endReason,
                context      = ClientContext(800, 600),
                testScenario = if (Config.hmrcSandbox) "STATEFUL" else null,
            )

            when (result) {
                is ApiResult.Success -> {
                    PropertyRepository.softDelete(selected.id)
                    withContext(Dispatchers.JavaFx) {
                        onDeleted()
                        onStatusChange("Foreign property ended with HMRC and deleted ✓")
                    }
                }
                is ApiResult.Failure -> {
                    withContext(Dispatchers.JavaFx) {
                        Dialogs.showError(
                            "Could not end the property with HMRC, so it has not been deleted " +
                                    "locally either — this keeps LibreMTD and HMRC in sync.\n\n${result.message}",
                            title = "HMRC update failed",
                        )
                    }
                }
            }
        }
    }

    private fun promptForEndDetails(address: String): ForeignPropertyEndDetails? {
        val reasonCombo = ComboBox<ForeignPropertyEndReason>().apply {
            items.setAll(ForeignPropertyEndReason.entries)
            converter = object : StringConverter<ForeignPropertyEndReason>() {
                override fun toString(reason: ForeignPropertyEndReason?) = reason?.displayName ?: ""
                override fun fromString(string: String?): ForeignPropertyEndReason? = null
            }
            value = ForeignPropertyEndReason.NO_LONGER_RENTING
        }
        val datePicker = DatePicker(java.time.LocalDate.now())

        val grid = GridPane().apply {
            hgap = 10.0; vgap = 10.0
            padding = Insets(4.0)
            addRow(0, Label("End date:"), datePicker)
            addRow(1, Label("Reason:"), reasonCombo)
        }

        val dialog = Dialog<ForeignPropertyEndDetails?>().apply {
            title = "End foreign property with HMRC"
            headerText = "Ending \"$address\" with HMRC before deleting it locally"
            dialogPane.content = grid
            dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
            setResultConverter { button ->
                if (button == ButtonType.OK) {
                    val date = datePicker.value
                    val reason = reasonCombo.value
                    if (date != null && reason != null) ForeignPropertyEndDetails(date.toString(), reason) else null
                } else null
            }
        }
        dialog.applyAppIcons()

        return dialog.showAndWait().orElse(null)
    }

    // ── Load / clear ────────────────────────────────────────────────────

    private fun loadProperties() {
        scope.launch(Dispatchers.IO) {
            val loaded = PropertyRepository.findByUser(userId)
            val foreign = loaded.filter { it.propertyType == PropertyType.FOREIGN }
            val summaries = foreign.associate { it.id to ftcrSummaryFor(it) }
            withContext(Dispatchers.JavaFx) {
                ukProperties.setAll(loaded.filter { it.propertyType == PropertyType.UK })
                foreignProperties.setAll(foreign)
                ftcrSummaries.clear()
                ftcrSummaries.putAll(summaries)
                onStatusChange("${loaded.size} property/properties loaded")
            }
        }
    }

    private fun clearForm() {
        addressField.clear()
        postcodeField.clear()
        countryCodeField.clear()
        ftcrCheckBox.isSelected = false
        ukRadio.isSelected = true
    }
}
