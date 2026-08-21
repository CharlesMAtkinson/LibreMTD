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

package org.charlesatkinson.libremtd.network

import mu.KotlinLogging

/**
 * Records HMRC API calls that received no response at all (network error,
 * timeout, etc.) or an HTTP 504, since these are the ambiguous cases where
 * it is unclear whether HMRC actually received and processed the request.
 *
 * Deliberately shared across every client in this package rather than
 * duplicated per client, so there is a single, easily greppable record of
 * what was sent and when, regardless of which endpoint was involved. This
 * exists independently of any particular later use of the record (support
 * queries, reconciliation, etc.) — it simply captures the fact.
 */
object ResponseStatusNullOr504 {
    private val logger = KotlinLogging.logger {}

    fun record(method: String, path: String, body: String?, statusCode: Int?) {
        val bodyNote = body?.let { "\nRequest body: $it" } ?: ""
        when (statusCode) {
            null -> logger.warn { "$method $path — no response received (network error or timeout)$bodyNote" }
            504  -> logger.warn { "$method $path — HTTP 504 Gateway Timeout$bodyNote" }
            else -> { /* not one of the cases this object cares about */ }
        }
    }
}