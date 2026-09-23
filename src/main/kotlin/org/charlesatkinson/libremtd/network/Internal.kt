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

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * The single shared JSON (de)serialiser configuration for every HMRC API
 * client in this package.
 *
 * This exists as its own file, rather than each client declaring its own
 * private instance, because a top-level declaration in one file that isn't
 * `private` becomes visible from every other file in the package — so
 * having several files each declare their own "json" only worked while all
 * of them stayed `private`. As soon as one needed to be `internal` (so a
 * test in the same package could exercise it directly — see
 * ForeignPropertyClientTest), it collided with every other file's own
 * "json". Consolidating to one instance here removes that trap for good:
 * there is now exactly one "json" in this package, and it is obvious where
 * it lives.
 *
 * Configuration:
 * - ignoreUnknownKeys: HMRC's response schemas evolve between API versions
 *   (see IndividualCalculationsClient's v8 field-renaming notes); this
 *   stops an unrecognised field from breaking parsing entirely.
 * - explicitNulls = false: required by ForeignPropertyClient.rename(),
 *   whose request body must omit endDate/endReason entirely rather than
 *   send them as explicit nulls. This setting only affects encoding
 *   (writing JSON) — it has no effect on decoding — so it's safe for every
 *   other client here, which only ever uses this instance to decode HMRC
 *   responses.
 */
@OptIn(ExperimentalSerializationApi::class)
internal val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
