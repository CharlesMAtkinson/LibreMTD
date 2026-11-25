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

package org.charlesatkinson.libremtd.security

import javafx.application.Platform
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.stage.Stage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A one-place gate: at most one holder at a time. Pure Kotlin with no
 * JavaFX, so it can be unit tested without the toolkit.
 *
 * [tryEnter] never blocks. It returns true to exactly one caller until
 * [leave] is called, even if many threads call it at the same moment.
 */
class AuthorisationGate {
    private val held = AtomicBoolean(false)

    /** True if the caller now holds the gate, false if somebody else does. */
    fun tryEnter(): Boolean = held.compareAndSet(false, true)

    /** Releases the gate. Harmless if it is not held, or if called twice. */
    fun leave() {
        held.set(false)
    }

    fun isHeld(): Boolean = held.get()
}

/**
 * Keeps the HMRC authorisation window to one at a time for the whole
 * process. A process has one logged-in user at a time, so a single
 * process-wide holder is enough, and it outlasts ConnectPane, which is
 * rebuilt every time the user clicks HMRC > Connect.
 *
 * Whoever starts an authorisation calls [tryBegin] on the JavaFX thread
 * and, whatever happens, [end] afterwards (a finally block). OAuth2Handler
 * registers the window it opens with [register], so that it can be brought
 * to the front by [bringToFront] and closed by [closeIfOpen].
 *
 * The truth is the gate itself, which is read with [isActive] and changes
 * at once on any thread. [activeProperty] is a JavaFX copy of it for
 * listeners. It is updated through Platform.runLater, so it follows a
 * moment later and always on the JavaFX thread.
 */
object AuthorisationWindow {
    private val gate = AuthorisationGate()

    private val activeWrapper = ReadOnlyBooleanWrapper(false)

    /** Observable copy of [isActive]. Changes on the JavaFX thread only. */
    val activeProperty: ReadOnlyBooleanProperty = activeWrapper.readOnlyProperty

    // The open window, if any. Read and written on the JavaFX thread only.
    private var stage: Stage? = null

    /** True while an authorisation is in progress. */
    fun isActive(): Boolean = gate.isHeld()

    /**
     * Tries to start an authorisation. False means one is already in
     * progress, and the caller must not open another window.
     */
    fun tryBegin(): Boolean = synchronized(gate) {
        if (!gate.tryEnter()) return false
        publish(true)
        true
    }

    /**
     * Ends the authorisation which [tryBegin] started. Safe to call on any
     * thread, and harmless if called twice.
     */
    fun end() {
        synchronized(gate) {
            gate.leave()
            publish(false)
        }
    }

    // Always queued, never run directly, so that updates reach the
    // property in the order in which the gate changed.
    private fun publish(active: Boolean) {
        Platform.runLater {
            activeWrapper.set(active)
            if (!active) stage = null
        }
    }

    /** Records the window just opened. JavaFX thread only. */
    fun register(window: Stage) {
        stage = window
    }

    /** Forgets [window] if it is the one recorded. JavaFX thread only. */
    fun unregister(window: Stage) {
        if (stage === window) stage = null
    }

    /** Brings the open window to the front, restoring it if minimised. JavaFX thread only. */
    fun bringToFront() {
        val window = stage ?: return
        if (!window.isShowing) return
        window.isIconified = false
        window.toFront()
        window.requestFocus()
    }

    /**
     * Closes the open window, if there is one. JavaFX thread only. The
     * window's hidden handler (see OAuth2Handler) then ends the waiting
     * authorisation as cancelled.
     */
    fun closeIfOpen() {
        stage?.close()
    }
}
