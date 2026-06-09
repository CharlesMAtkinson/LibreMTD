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

package org.charlesatkinson.libremtd.utils

/**
 * Typed result wrapper for HMRC API calls and any other operations that can
 * fail with a user-visible message.
 *
 * Use instead of returning null or throwing exceptions at the network boundary,
 * so callers can pattern-match cleanly:
 *
 *   when (val r = client.fetchSomething(...)) {
 *       is ApiResult.Success -> use(r.data)
 *       is ApiResult.Failure -> showError(r.message)
 *   }
 */
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Failure(val message: String, val cause: Throwable? = null) : ApiResult<Nothing>()
}
