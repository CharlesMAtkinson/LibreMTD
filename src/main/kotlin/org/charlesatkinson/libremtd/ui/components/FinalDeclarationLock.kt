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

package org.charlesatkinson.libremtd.ui.components

import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.scene.Node
import javafx.scene.control.Label

/**
 * Shared UI behaviour for the Final Declaration gate: an inline banner that
 * appears when the currently-viewed tax year has been finally declared, and
 * a helper to disable a pane's entry controls (category pickers, amount
 * fields, Add/Delete buttons and so on) while that banner is showing.
 *
 * This is a proactive UX layer only – it improves the experience by
 * preventing wasted effort, but it is not the enforcement point. The
 * repository layer (see database.components.FinalDeclarationGuard) is the
 * authoritative gate and must still be relied on, since this lock cannot
 * account for e.g. a final declaration happening in another window while
 * a pane is open with controls already enabled.
 *
 * IMPORTANT – construction order: declare an instance of this class BEFORE
 * any PropertySelector/PeriodSelector/TaxYearSelector field in the same
 * pane. Those selectors may invoke their callback synchronously from
 * within their own constructor (to report their initial selection), and
 * if that callback calls back into this lock's [update] before this
 * field's own initialiser has run, the call fails with a
 * NullPointerException – Kotlin property initialisers run strictly in
 * declaration order, so a field used before its own line is reached is
 * still null. See the development log entry for 2026-09-02 for the actual
 * incident this note is guarding against.
 *
 * One instance is shared per pane (or per tax-year-scoped section of a
 * pane, for panes such as DividendIncomePane with several sections that
 * share one tax year).
 *
 * TWO WAYS TO DISABLE A CONTROL. A control which should simply be disabled
 * while the year is locked (a text field, a category picker, the Add
 * button) is passed to [update]. A control whose disabled state also
 * depends on something else (the Edit and Delete buttons under an entries
 * table, which are also disabled while no row is selected) must NOT be
 * passed to [update]: update sets isDisable directly, which JavaFX refuses
 * for a property that is bound, and would in any case re-enable the button
 * when the year is unlocked whether or not a row is selected. Bind such a
 * control's disabled property to an expression which includes
 * [lockedProperty] instead.
 */
class FinalDeclarationLock {

    /** Insert this into the pane's layout, above the entry form it guards.
     *  Hidden and unmanaged (takes no layout space) when not locked. */
    val banner: Label = wrappingLabel("").apply {
        styleClass.addAll("warning-banner", "warning-banner-text")
        isVisible = false
        isManaged = false
    }

    private val lockedWrapper = ReadOnlyBooleanWrapper(false)

    /** True while the most recent [update] call reported the year as
     *  finally declared. Observable, so a control can bind its disabled
     *  state to it – see the class comment. Read-only: only [update]
     *  changes it. */
    val lockedProperty: ReadOnlyBooleanProperty = lockedWrapper.readOnlyProperty

    /** True if the most recent [update] call reported the year as finally
     *  declared. Useful for a defensive check before firing an action, in
     *  addition to relying on disabled controls. */
    fun isLocked(): Boolean = lockedWrapper.get()

    /**
     * Call whenever the pane's tax year (or period) changes, or after
     * loading entries for it. Updates the banner text and visibility, and
     * disables (or re-enables) every control passed in [controls].
     *
     * [controls] should list every input and button that would otherwise
     * let the user create, edit or delete an entry – category pickers,
     * text fields, checkboxes and the Add button – EXCEPT controls whose
     * disabled property is bound (see the class comment). Read-only
     * elements such as the entries table itself do not need to be
     * included, since viewing past entries remains allowed once a year is
     * locked.
     */
    fun update(isFinalDeclared: Boolean, taxYear: String, vararg controls: Node) {
        lockedWrapper.set(isFinalDeclared)
        banner.text = if (isFinalDeclared)
            "Tax year $taxYear has been finally declared and can no longer be amended."
        else ""
        banner.isVisible = isFinalDeclared
        banner.isManaged = isFinalDeclared
        controls.forEach { it.isDisable = isFinalDeclared }
    }
}
