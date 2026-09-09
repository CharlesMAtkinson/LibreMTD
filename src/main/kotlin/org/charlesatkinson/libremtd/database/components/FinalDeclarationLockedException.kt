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

package org.charlesatkinson.libremtd.database.components

/**
 * Thrown when code attempts to create, edit or delete an income, expense,
 * allowance or foreign property election entry for a tax year that has
 * already been finally declared to HMRC for the relevant user. UI code
 * should catch this specifically and show a friendly message, rather than
 * a generic error dialog.
 */
class FinalDeclarationLockedException(val taxYear: String) :
    RuntimeException("Tax year $taxYear has been finally declared and can no longer be amended.")
