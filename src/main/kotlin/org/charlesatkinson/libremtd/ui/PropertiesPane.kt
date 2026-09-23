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

import javafx.event.ActionEvent
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
import org.charlesatkinson.libremtd.database.ExpensePropertyForeignRepository
import org.charlesatkinson.libremtd.database.ExpensePropertyUkRepository
import org.charlesatkinson.libremtd.database.ForeignPropertyElection
import org.charlesatkinson.libremtd.database.ForeignPropertyElectionRepository
import org.charlesatkinson.libremtd.database.IncomePropertyForeignRepository
import org.charlesatkinson.libremtd.database.IncomePropertyUkRepository
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

private data class ForeignPropertyEndDetails(
    val endDate: String,
    val endReason: ForeignPropertyEndReason,
)

/** Result of the "Edit property" dialogue for UK properties — see [PropertiesPane.promptForUkEdit]. */
private data class UkEditDetails(
    val address: String,
    val postcode: String,
)

/** Result of the "Edit property" dialogue for foreign properties — see [PropertiesPane.promptForForeignEdit]. */
private data class ForeignEditDetails(
    val address: String,
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
    // "2026-27 ✓ · 2027-28 ✗". Recomputed in loadProperties(); read (not
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
                hintLabel(
                    "Manage your let properties. Income and expenses are recorded per property " +
                            "and aggregated across all properties when submitting to HMRC."
                ),
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

        // ukRadio is already selected by the time this method runs (see its
        // field initialiser above), which happened before the listener just
        // above existed — so without this call, the fields hidden a few
        // lines up would never be revealed until the user actively changed
        // the toggle. This syncs the visible state to the actual initial
        // selection, the same way any later click does.
        handleTypeSelected()

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

    /**
     * Every LibreMTD-supported tax year (2026-27 onwards — see
     * database.availableTaxYears) requires HMRC registration for a new
     * foreign property, so this hint no longer needs to branch on the
     * selected year; it's kept as a hint (rather than removed) because it's
     * still useful to tell the user what's about to happen.
     */
    private fun updateForeignHint(taxYear: String?) {
        if (!::foreignHintLabel.isInitialized) return
        foreignHintLabel.text = if (taxYear.isNullOrBlank())
            ""
        else
            "This property will be registered with HMRC when you click \"Add property\", " +
                    "and assigned an HMRC property ID which will appear in the table below."
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
            else -> addForeignPropertyViaHmrc(address, country, taxYear, ftcr)
        }
    }

    /** HMRC issues a UUID propertyID for every foreign property in every
     *  LibreMTD-supported tax year, so a live connection and business ID
     *  are always required to add one — there is no local-only path. */
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

    // ── Edit a UK property's address/postcode ──────────────────────────────

    /**
     * Opens an "Edit property" dialogue pre-filled with [selected]'s current
     * address and postcode, validates the input the same way as the "Add
     * property" form, and — if the user confirms — persists the change via
     * [PropertyRepository.updateUk].
     *
     * This is a plain in-place correction: no confirmation-of-intent dialog
     * beyond the edit form itself, no tax-year lock, and no HMRC call —
     * see PropertyRepository.updateUk's doc comment for why that is correct
     * for UK properties specifically.
     */
    private fun handleEditUk(selected: Property, onEdited: () -> Unit) {
        val edited = promptForUkEdit(selected) ?: return

        scope.launch(Dispatchers.IO) {
            PropertyRepository.updateUk(selected.id, edited.address, edited.postcode)
            withContext(Dispatchers.JavaFx) {
                onEdited()
                onStatusChange("Property updated ✓")
            }
        }
    }

    /**
     * Shows the edit dialog and returns the corrected values, or null if
     * the user cancelled. Validation mirrors handleAddUk(): re-uses the
     * same UK_POSTCODE_REGEX, and the OK button is blocked (via an
     * ActionEvent filter that consumes the event) from closing the dialog
     * until the fields are valid, so the user sees the error inline rather
     * than the dialog vanishing and a separate error alert appearing.
     */
    private fun promptForUkEdit(selected: Property): UkEditDetails? {
        val addressField  = TextField(selected.address).apply { prefWidth = 300.0 }
        val postcodeField = TextField(selected.postcode ?: "").apply { prefWidth = 120.0 }
        val errorLabel    = wrappingLabel("").apply { styleClass.add("status-error") }

        val grid = GridPane().apply {
            hgap = 10.0; vgap = 10.0
            padding = Insets(4.0)
            addRow(0, Label("Address:"), addressField)
            addRow(1, Label("Postcode:"), postcodeField)
        }

        val dialog = Dialog<UkEditDetails?>().apply {
            title = "Edit property"
            headerText = "Edit ${selected.address}"
            dialogPane.content = VBox(8.0, grid, errorLabel).also { it.padding = Insets(4.0) }
            dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

            val okButton = dialogPane.lookupButton(ButtonType.OK)
            okButton.addEventFilter(ActionEvent.ACTION) { event ->
                val address  = addressField.text.trim()
                val postcode = postcodeField.text.trim().uppercase()
                val errors = mutableListOf<String>()
                if (address.isBlank()) errors += "Please enter an address."
                if (postcode.isBlank()) errors += "Please enter a postcode."
                else if (!UK_POSTCODE_REGEX.matches(postcode))
                    errors += "Please enter a valid UK postcode (e.g. SW1A 1AA)."

                if (errors.isNotEmpty()) {
                    errorLabel.text = errors.joinToString("\n")
                    event.consume()
                }
            }

            setResultConverter { button ->
                if (button == ButtonType.OK)
                    UkEditDetails(addressField.text.trim(), postcodeField.text.trim().uppercase())
                else
                    null
            }
        }
        dialog.applyAppIcons()

        return dialog.showAndWait().orElse(null)
    }

    // ── Edit a foreign property's address ───────────────────────────────

    /**
     * Foreign property renaming, in two branches:
     *
     * - Not registered with HMRC (hmrcPropertyId == null): under LibreMTD's
     *   normal Add Property flow this cannot happen any more — every
     *   foreign property is now registered with HMRC at creation time (see
     *   addForeignPropertyViaHmrc). This branch only matters for a property
     *   that reached the database some other way, e.g. a direct sqlite3
     *   edit or a future import feature — in that case this is a plain
     *   local correction exactly like [handleEditUk].
     * - Registered with HMRC: the address WAS sent to HMRC at registration,
     *   so HMRC's own Update Foreign Property Details endpoint must be
     *   called first (see ForeignPropertyClient.rename()). The local record
     *   is only updated once HMRC confirms the change, so LibreMTD and HMRC
     *   never disagree about the address — the same "HMRC first, then
     *   local" pattern as [handleEndRegisteredForeignProperty].
     *
     * Country code is never offered for editing here: HMRC's Update Foreign
     * Property Details endpoint has no field for it, so a wrong country
     * code still requires the delete/re-register route.
     */
    private fun handleEditForeign(selected: Property, onEdited: () -> Unit) {
        val edited = promptForForeignEdit(selected) ?: return

        if (selected.hmrcPropertyId == null) {
            scope.launch(Dispatchers.IO) {
                PropertyRepository.updateForeignAddress(selected.id, edited.address)
                withContext(Dispatchers.JavaFx) {
                    onEdited()
                    onStatusChange("Property updated ✓")
                }
            }
            return
        }

        val taxYear = selected.hmrcRegisteredTaxYear
        if (taxYear.isNullOrBlank()) {
            Dialogs.showError(
                "This property has an HMRC property ID but no recorded registration tax year " +
                        "— likely registered before this tracking was added. It can't be safely " +
                        "renamed with HMRC automatically. Please check its record on HMRC directly, " +
                        "or if you know the tax year it was registered under, that can be backfilled " +
                        "in the database.",
                title = "Missing registration tax year",
            )
            return
        }

        scope.launch(Dispatchers.IO) {
            val settings = requireConnectedSettingsOrShowError() ?: return@launch

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

            val result = ForeignPropertyClient(client).rename(
                nino         = settings.nino,
                propertyId   = selected.hmrcPropertyId!!,
                taxYear      = taxYear,
                propertyName = edited.address,
                context      = ClientContext(800, 600),
                testScenario = if (Config.hmrcSandbox) "STATEFUL" else null,
            )

            when (result) {
                is ApiResult.Success -> {
                    PropertyRepository.updateForeignAddress(selected.id, edited.address)
                    withContext(Dispatchers.JavaFx) {
                        onEdited()
                        onStatusChange("Foreign property renamed with HMRC and locally ✓")
                    }
                }
                is ApiResult.Failure -> {
                    withContext(Dispatchers.JavaFx) {
                        Dialogs.showError(
                            "Could not rename the property with HMRC, so the local address has " +
                                    "not been changed either — this keeps LibreMTD and HMRC in sync.\n\n" +
                                    result.message,
                            title = "HMRC update failed",
                        )
                    }
                }
            }
        }
    }

    private fun promptForForeignEdit(selected: Property): ForeignEditDetails? {
        val addressField = TextField(selected.address).apply { prefWidth = 300.0 }
        val errorLabel    = wrappingLabel("").apply { styleClass.add("status-error") }

        val noteText = if (selected.hmrcPropertyId != null)
            "This will update the address with HMRC as well as locally. The country code cannot be changed here."
        else
            "This property is not registered with HMRC, so this change is local only. " +
                    "The country code cannot be changed here."

        val grid = GridPane().apply {
            hgap = 10.0; vgap = 10.0
            padding = Insets(4.0)
            addRow(0, Label("Address:"), addressField)
        }

        val dialog = Dialog<ForeignEditDetails?>().apply {
            title = "Edit property"
            headerText = "Edit ${selected.address}"
            dialogPane.content = VBox(8.0, grid, hintLabel(noteText), errorLabel).also { it.padding = Insets(4.0) }
            dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

            val okButton = dialogPane.lookupButton(ButtonType.OK)
            okButton.addEventFilter(ActionEvent.ACTION) { event ->
                val address = addressField.text.trim()
                if (address.isBlank()) {
                    errorLabel.text = "Please enter an address."
                    event.consume()
                }
            }

            setResultConverter { button ->
                if (button == ButtonType.OK) ForeignEditDetails(addressField.text.trim()) else null
            }
        }
        dialog.applyAppIcons()

        return dialog.showAndWait().orElse(null)
    }

    // ── Register an existing local-only foreign property with HMRC ────────

    /**
     * Registers a foreign property that exists locally without an HMRC
     * propertyId. Under LibreMTD's normal Add Property flow this button
     * should never be needed — every new foreign property is registered at
     * creation time (see addForeignPropertyViaHmrc) — but it's kept as a
     * recovery path for a property that reached the database some other
     * way, e.g. a direct sqlite3 edit or a future import feature.
     */
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
        val eligibleYears = availableTaxYears()
        if (eligibleYears.isEmpty()) {
            Dialogs.showError(
                "No tax years are currently available to register this property under.",
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
     * HMRC or added locally, and NOT bounded by whether the property has
     * since been ended: hmrcRegisteredTaxYear records when HMRC registration
     * happened, not when the letting started; ending a property only tells
     * HMRC/LibreMTD the letting has stopped from a given date, it doesn't
     * retroactively close tax years that still need FTCR reviewed. The
     * trade-off is that a property will also show FTCR options for years
     * before it existed at all; harmless, since there's simply nothing to
     * submit for those years.
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

    /** Greys out an entire row when the property has been ended, so ended
     *  properties stay visible and selectable but are visually distinct
     *  from active ones. */
    private fun applyEndedRowStyling(table: TableView<Property>) {
        table.setRowFactory {
            object : TableRow<Property>() {
                override fun updateItem(item: Property?, empty: Boolean) {
                    super.updateItem(item, empty)
                    style = if (!empty && item?.endedAt != null) "-fx-opacity: 0.55;" else ""
                }
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
                TableColumn<Property, String>("Ended").apply {
                    prefWidth = 120.0; maxWidth = 140.0; minWidth = 100.0
                    setCellValueFactory { SimpleStringProperty(it.value.endedAt?.take(10) ?: "") }
                    setCellFactory { centeredCell() }
                },
            )
        }
        applyEndedRowStyling(table)

        val editBtn   = buildEditUkButton(table) { loadProperties() }
        val endBtn    = buildEndButton(table) { loadProperties() }
        val removeBtn = buildRemoveButton(table) { loadProperties() }

        return VBox(8.0).apply {
            padding = Insets(12.0, 16.0, 12.0, 16.0)
            styleClass.add("content-card")
            style   = "-fx-border-radius: 8; -fx-background-radius: 8;"
            children.addAll(
                wrappingLabel("Your UK properties").apply { style = "-fx-font-weight: bold;" },
                Separator(),
                table,
                HBox(10.0, editBtn, endBtn, removeBtn),
            )
        }
    }

    /**
     * Builds the "Edit…" button shown against the UK properties table.
     * Selection is checked at click time (matching the End/Remove buttons'
     * pattern below) rather than via a selection listener, since — unlike
     * Register with HMRC or FTCR — there is no async eligibility check to
     * run ahead of enabling it: any selected UK property can be edited.
     */
    private fun buildEditUkButton(table: TableView<Property>, onEdited: () -> Unit): Button {
        return Button("Edit…").apply {
            styleClass.add("primary-action-button")
            setOnAction {
                val selected = table.selectionModel.selectedItem
                if (selected == null) {
                    Dialogs.showError("Please select a property to edit.")
                } else {
                    handleEditUk(selected, onEdited)
                }
            }
        }
    }

    /** As [buildEditUkButton], for the foreign properties table. Any
     *  selected foreign property can be edited — eligibility for the HMRC
     *  call (if any) is worked out inside handleEditForeign() itself. */
    private fun buildEditForeignButton(table: TableView<Property>, onEdited: () -> Unit): Button {
        return Button("Edit…").apply {
            styleClass.add("primary-action-button")
            setOnAction {
                val selected = table.selectionModel.selectedItem
                if (selected == null) {
                    Dialogs.showError("Please select a property to edit.")
                } else {
                    handleEditForeign(selected, onEdited)
                }
            }
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
                TableColumn<Property, String>("Ended").apply {
                    prefWidth = 120.0; maxWidth = 140.0; minWidth = 100.0
                    setCellValueFactory { SimpleStringProperty(it.value.endedAt?.take(10) ?: "") }
                    setCellFactory { centeredCell() }
                },
                TableColumn<Property, String>("FTCR").apply {
                    prefWidth = 160.0; maxWidth = 220.0; minWidth = 120.0
                    setCellValueFactory { SimpleStringProperty(ftcrSummaries[it.value.id] ?: "…") }
                    setCellFactory { centeredCell() }
                },
            )
        }
        applyEndedRowStyling(table)

        registerHmrcBtn = Button("Register with HMRC").apply {
            styleClass.add("primary-action-button")
            isDisable = true
            setOnAction {
                val selected = table.selectionModel.selectedItem
                when {
                    selected == null -> Dialogs.showError("Please select a property to register.")
                    selected.hmrcPropertyId != null -> Dialogs.showError("This property is already registered with HMRC.")
                    selected.endedAt != null -> Dialogs.showError("This property has already ended, so it can't be registered with HMRC.")
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
            registerHmrcBtn.isDisable = selected == null || selected.hmrcPropertyId != null || selected.endedAt != null
            ftcrBtn.isDisable = selected == null
        }

        val editBtn   = buildEditForeignButton(table) { loadProperties() }
        val endBtn    = buildEndButton(table) { loadProperties() }
        val removeBtn = buildRemoveButton(table) { loadProperties() }

        return VBox(8.0).apply {
            padding = Insets(12.0, 16.0, 12.0, 16.0)
            styleClass.add("content-card")
            style   = "-fx-border-radius: 8; -fx-background-radius: 8;"
            children.addAll(
                wrappingLabel("Your foreign properties").apply { style = "-fx-font-weight: bold;" },
                Separator(),
                table,
                HBox(10.0, editBtn, registerHmrcBtn, ftcrBtn, endBtn, removeBtn),
            )
        }
    }

    // ── End letting ─────────────────────────────────────────────────────

    private fun buildEndButton(table: TableView<Property>, onEnded: () -> Unit): Button {
        return Button("End letting…").apply {
            styleClass.add("primary-action-button")
            setOnAction {
                val selected = table.selectionModel.selectedItem
                when {
                    selected == null -> {
                        Dialogs.showError("Please select a property to end.")
                    }
                    selected.endedAt != null -> {
                        Dialogs.showError("This property has already ended.")
                    }
                    selected.propertyType == PropertyType.FOREIGN && selected.hmrcPropertyId != null -> {
                        handleEndRegisteredForeignProperty(selected, onEnded)
                    }
                    else -> {
                        confirmAndEnd(selected, onEnded)
                    }
                }
            }
        }
    }

    private fun confirmAndEnd(selected: Property, onEnded: () -> Unit) {
        val confirmed = Alert(Alert.AlertType.CONFIRMATION).apply {
            title       = "End letting"
            headerText  = "End the letting of ${selected.address}?"
            contentText = "This will mark the property as ended. Existing income and expense " +
                    "entries for this property are retained and remain available for review " +
                    "and amendment until you make your Final Declaration."
        }.showAndWait().map { it.buttonData == ButtonBar.ButtonData.OK_DONE }.orElse(false)

        if (!confirmed) return

        scope.launch(Dispatchers.IO) {
            PropertyRepository.end(selected.id)
            withContext(Dispatchers.JavaFx) {
                onEnded()
                onStatusChange("Property ended")
            }
        }
    }

    /**
     * Ending a foreign property that's registered with HMRC must first end
     * it there via Update Foreign Property Details (there's no separate
     * delete endpoint) — otherwise the record stays "active" on HMRC's side
     * while LibreMTD thinks it's ended. If the HMRC call fails, we deliberately
     * do NOT mark it ended locally, to keep the two in sync.
     */
    private fun handleEndRegisteredForeignProperty(selected: Property, onEnded: () -> Unit) {
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
                    PropertyRepository.end(selected.id)
                    withContext(Dispatchers.JavaFx) {
                        onEnded()
                        onStatusChange("Foreign property ended with HMRC and locally ✓")
                    }
                }
                is ApiResult.Failure -> {
                    withContext(Dispatchers.JavaFx) {
                        Dialogs.showError(
                            "Could not end the property with HMRC, so it has not been marked as " +
                                    "ended locally either — this keeps LibreMTD and HMRC in sync.\n\n${result.message}",
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
            headerText = "Ending \"$address\" with HMRC before marking it as ended locally"
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

    // ── Remove ──────────────────────────────────────────────────────────

    /**
     * True only if the property has never had an income or expense entry
     * recorded against it (checking full history, not just current rows —
     * even a superseded entry is evidence of real use). FTCR election rows
     * are deliberately not checked; see ForeignPropertyElectionRepository's
     * "Do we care about this one?" discussion — every foreign property gets
     * an election row automatically on creation, so treating that as "real
     * use" would mean no foreign property could ever be removed.
     */
    private fun hasNoEntries(property: Property): Boolean {
        return when (property.propertyType) {
            PropertyType.UK ->
                !IncomePropertyUkRepository.existsForProperty(property.id) &&
                        !ExpensePropertyUkRepository.existsForProperty(property.id)
            PropertyType.FOREIGN ->
                !IncomePropertyForeignRepository.existsForProperty(property.id) &&
                        !ExpensePropertyForeignRepository.existsForProperty(property.id)
        }
    }

    /**
     * Builds the Remove button and wires it to the table's selection so its
     * enabled state and colour reflect whether the *currently selected*
     * property can actually be removed — rather than always looking the
     * same and only revealing whether removal is possible once clicked.
     *
     * The check needs a database read, so it's done asynchronously on
     * selection change; [checkToken] guards against a stale check landing
     * after the user has since selected something else.
     */
    private fun buildRemoveButton(table: TableView<Property>, onRemoved: () -> Unit): Button {
        val button = Button("Remove").apply {
            isDisable = true
        }

        var checkToken = 0

        table.selectionModel.selectedItemProperty().addListener { _, _, selected ->
            checkToken++
            val thisToken = checkToken

            if (selected == null) {
                button.isDisable = true
                button.styleClass.remove("primary-action-button")
                return@addListener
            }

            // Disabled and un-highlighted while we check — avoids a flash
            // of "removable" styling carried over from the previous selection.
            button.isDisable = true
            button.styleClass.remove("primary-action-button")

            scope.launch(Dispatchers.IO) {
                val removable = hasNoEntries(selected)
                withContext(Dispatchers.JavaFx) {
                    // Selection may have changed again while the check was
                    // running — ignore a result that's no longer current.
                    if (thisToken != checkToken) return@withContext
                    button.isDisable = !removable
                    if (removable) {
                        if (!button.styleClass.contains("primary-action-button")) {
                            button.styleClass.add("primary-action-button")
                        }
                    } else {
                        button.styleClass.remove("primary-action-button")
                    }
                }
            }
        }

        button.setOnAction {
            val selected = table.selectionModel.selectedItem
            // The button is only enabled once hasNoEntries() has already
            // been confirmed true for this selection, via the listener
            // above — so no need to repeat that check here.
            if (selected != null) confirmAndRemove(selected, onRemoved)
        }

        return button
    }

    private fun confirmAndRemove(selected: Property, onRemoved: () -> Unit) {
        val confirmed = Alert(Alert.AlertType.CONFIRMATION).apply {
            title       = "Remove property"
            headerText  = "Permanently remove ${selected.address}?"
            contentText = "This property has no income or expense entries recorded, so it will " +
                    "be deleted completely rather than just ended. This cannot be undone."
        }.showAndWait().map { it.buttonData == ButtonBar.ButtonData.OK_DONE }.orElse(false)

        if (!confirmed) return

        scope.launch(Dispatchers.IO) {
            PropertyRepository.remove(selected.id)
            withContext(Dispatchers.JavaFx) {
                onRemoved()
                onStatusChange("Property removed")
            }
        }
    }

    // ── Load / clear ────────────────────────────────────────────────────

    private fun loadProperties() {
        scope.launch(Dispatchers.IO) {
            val loaded = PropertyRepository.findByUser(userId)
            val foreign = loaded.filter { it.propertyType == PropertyType.FOREIGN }
            val summaries = foreign.associate { it.id to ftcrSummaryFor(it) }
            // Active properties first, then ended ones, each alphabetically —
            // ended properties stay visible for review rather than
            // disappearing, but shouldn't clutter the top of the table.
            val sortedUk = loaded.filter { it.propertyType == PropertyType.UK }
                .sortedWith(compareBy({ it.endedAt != null }, { it.address }))
            val sortedForeign = foreign
                .sortedWith(compareBy({ it.endedAt != null }, { it.address }))
            withContext(Dispatchers.JavaFx) {
                ukProperties.setAll(sortedUk)
                foreignProperties.setAll(sortedForeign)
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
