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

import org.charlesatkinson.libremtd.network.ClientContext
import org.charlesatkinson.libremtd.utils.Config
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FraudPreventionHeadersTest {

    private val fraudHeaders = FraudPreventionHeaders()
    private val context = ClientContext(windowWidth = 1024, windowHeight = 768)

    @Test
    fun `buildHeaders includes all required headers`() {
        val headers = fraudHeaders.buildHeaders(context)
        assertTrue(fraudHeaders.validateHeaders(headers))
    }

    @Test
    fun `Gov-Vendor-Version is derived from Config VERSION`() {
        val headers = fraudHeaders.buildHeaders(context)
        val vendorVersion = headers["Gov-Vendor-Version"]

        assertNotNull(vendorVersion)
        // "1.0.0-SNAPSHOT" contains only unreserved characters, so percent
        // encoding should leave it unchanged; this assertion will still pass
        // if VERSION is later bumped, as long as it stays free of reserved chars.
        assertEquals("${Config.APP_NAME}=${Config.VERSION}", vendorVersion)
    }

    @Test
    fun `buildKeyValueHeader percent-encodes reserved characters`() {
        // Simulates what would happen if a value ever contained a reserved
        // character (e.g. a space or plus sign), to prove the encoding path works.
        val encoded = fraudHeaders.buildKeyValueHeader("name" to "a b+c")
        assertEquals("name=a%20b%2Bc", encoded)
    }

    @Test
    fun `percentEncode leaves unreserved characters untouched`() {
        assertEquals("1.0.0-SNAPSHOT", fraudHeaders.percentEncode("1.0.0-SNAPSHOT"))
    }

    @Test
    fun `Gov-Client-User-Agent uses the required key-value structure`() {
        val headers = fraudHeaders.buildHeaders(context)
        val userAgent = headers["Gov-Client-User-Agent"]

        assertNotNull(userAgent)
        // Must contain exactly these four keys, in the key=value&key=value form,
        // per https://developer.service.hmrc.gov.uk/guides/fraud-prevention/connection-method/desktop-app-direct/
        val pairs = userAgent!!.split("&").associate {
            val (k, v) = it.split("=", limit = 2)
            k to v
        }

        assertEquals(setOf("os-family", "os-version", "device-manufacturer", "device-model"), pairs.keys)
        assertTrue(pairs["os-family"]!!.isNotBlank(), "os-family must not be blank")
        assertTrue(pairs["os-version"]!!.isNotBlank(), "os-version must not be blank")
        // device-manufacturer / device-model are allowed to be empty (advisory only)
    }

    @Test
    fun `Gov-Client-Local-IPs-Timestamp is present in UTC ISO-8601 format`() {
        val headers = fraudHeaders.buildHeaders(context)
        val timestamp = headers["Gov-Client-Local-IPs-Timestamp"]

        assertNotNull(timestamp)
        assertTrue(
            timestamp!!.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""")),
            "Expected yyyy-MM-ddTHH:mm:ss.SSSZ, got: $timestamp"
        )
    }

    @Test
    fun `Gov-Vendor-Product-Name is present and percent-encoded`() {
        val headers = fraudHeaders.buildHeaders(context)
        assertEquals(fraudHeaders.percentEncode(Config.APP_NAME), headers["Gov-Vendor-Product-Name"])
    }

    @Test
    fun `Gov-Vendor-License-IDs is present with a persistent hashed pseudo-license`() {
        val headers1 = fraudHeaders.buildHeaders(context)
        val headers2 = fraudHeaders.buildHeaders(context)
        val license1 = headers1["Gov-Vendor-License-IDs"]
        val license2 = headers2["Gov-Vendor-License-IDs"]

        assertNotNull(license1)
        assertTrue(
            license1!!.matches(Regex("^${Regex.escape(Config.APP_NAME)}=[0-9a-f]{64}$")),
            "Expected ${Config.APP_NAME}=<64 hex chars>, got: $license1"
        )
        assertEquals(license1, license2, "Should be stable across calls within the same installation")
    }

    @Test
    fun `Gov-Client-Local-IPs values are percent-encoded`() {
        val headers = fraudHeaders.buildHeaders(context)
        val localIps = headers["Gov-Client-Local-IPs"]!!
        // Any IPv6 address present would leave a raw colon if not encoded
        assertFalse(localIps.contains(":"), "Expected no raw colons in: $localIps")
    }

    @Test
    fun `Gov-Client-MAC-Addresses values are percent-encoded`() {
        val headers = fraudHeaders.buildHeaders(context)
        val macs = headers["Gov-Client-MAC-Addresses"]!!
        assertFalse(macs.contains(":"), "Expected no raw colons in: $macs")
    }

    @Test
    fun `Gov-Client-User-Agent no longer contains the app name or version`() {
        val headers = fraudHeaders.buildHeaders(context)
        val userAgent = headers["Gov-Client-User-Agent"]!!

        assertFalse(userAgent.contains(Config.APP_NAME), "Gov-Client-User-Agent should not carry the app name")
        assertFalse(userAgent.contains(Config.VERSION), "Gov-Client-User-Agent should not carry the app version")
    }
}