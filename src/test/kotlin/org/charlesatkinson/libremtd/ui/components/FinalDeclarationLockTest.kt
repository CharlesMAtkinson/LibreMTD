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

package org.charlesatkinson.libremtd.ui.components

import javafx.beans.property.SimpleObjectProperty
import javafx.scene.control.Button
import javafx.scene.control.TextField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension

/**
 * @ExtendWith is needed because the lock builds a Label, and Button and
 * TextField are used below, all of which require the JavaFX toolkit.
 * No Stage is shown.
 */
@ExtendWith(ApplicationExtension::class)
class FinalDeclarationLockTest {

    @Test
    fun `a new lock is not locked and its banner is hidden`() {
        val lock = FinalDeclarationLock()

        assertFalse(lock.isLocked())
        assertFalse(lock.lockedProperty.get())
        assertFalse(lock.banner.isVisible)
        assertFalse(lock.banner.isManaged)
    }

    @Test
    fun `locking shows the banner with the tax year and disables the listed controls`() {
        val lock  = FinalDeclarationLock()
        val field = TextField()
        val addBtn = Button("Add")

        lock.update(true, "2026-27", field, addBtn)

        assertTrue(lock.isLocked())
        assertTrue(lock.lockedProperty.get())
        assertTrue(lock.banner.isVisible)
        assertTrue(lock.banner.isManaged)
        assertTrue(lock.banner.text.contains("2026-27"))
        assertTrue(field.isDisable)
        assertTrue(addBtn.isDisable)
    }

    @Test
    fun `unlocking hides the banner and re-enables the listed controls`() {
        val lock  = FinalDeclarationLock()
        val field = TextField()
        lock.update(true, "2026-27", field)

        lock.update(false, "2026-27", field)

        assertFalse(lock.isLocked())
        assertFalse(lock.banner.isVisible)
        assertFalse(lock.banner.isManaged)
        assertEquals("", lock.banner.text)
        assertFalse(field.isDisable)
    }

    @Test
    fun `a control that is not listed is left alone`() {
        val lock     = FinalDeclarationLock()
        val unlisted = Button("Unlisted")

        lock.update(true, "2026-27")

        assertFalse(unlisted.isDisable)
    }

    @Test
    fun `lockedProperty notifies listeners only when the state actually changes`() {
        val lock = FinalDeclarationLock()
        var changes = 0
        lock.lockedProperty.addListener { _, _, _ -> changes++ }

        lock.update(false, "2026-27")   // no change
        lock.update(true, "2026-27")    // change
        lock.update(true, "2026-27")    // no change
        lock.update(false, "2026-27")   // change

        assertEquals(2, changes)
    }

    @Test
    fun `a button bound to lockedProperty follows the lock without being passed to update`() {
        val lock   = FinalDeclarationLock()
        val button = Button("Edit")
        button.disableProperty().bind(lock.lockedProperty)

        assertFalse(button.isDisable)
        lock.update(true, "2026-27")
        assertTrue(button.isDisable)
        lock.update(false, "2026-27")
        assertFalse(button.isDisable)
    }

    @Test
    fun `a button bound to selection and the lock is disabled with no selection even when unlocked`() {
        val lock      = FinalDeclarationLock()
        val selection = SimpleObjectProperty<String?>(null)
        val button    = Button("Delete")
        button.disableProperty().bind(selection.isNull().or(lock.lockedProperty))

        assertTrue(button.isDisable, "No row selected")

        selection.set("a row")
        assertFalse(button.isDisable, "Row selected and unlocked")

        lock.update(true, "2026-27")
        assertTrue(button.isDisable, "Row selected but locked")

        lock.update(false, "2026-27")
        assertFalse(button.isDisable, "Unlocked again with the row still selected")

        selection.set(null)
        assertTrue(button.isDisable, "Selection cleared")
    }
}
