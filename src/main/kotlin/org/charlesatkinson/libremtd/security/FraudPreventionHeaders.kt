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

package org.charlesatkinson.libremtd.security

import mu.KotlinLogging
import org.charlesatkinson.libremtd.utils.AppPaths.deviceIdFile
import org.charlesatkinson.libremtd.utils.Config
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.io.path.readText
import org.charlesatkinson.libremtd.network.ClientContext

/**
 * Generates fraud prevention headers required by HMRC Making Tax Digital API
 * See: https://developer.service.hmrc.gov.uk/guides/fraud-prevention/
 * Header format for this connection method (DESKTOP_APP_DIRECT):
 * https://developer.service.hmrc.gov.uk/guides/fraud-prevention/connection-method/desktop-app-direct/
 */
class FraudPreventionHeaders {
    private val logger = KotlinLogging.logger {}

    /**
     * Gets all required fraud prevention headers for API requests
     */
    fun buildHeaders(context: ClientContext): Map<String, String> {
        val headers = mutableMapOf<String, String>()

        try {
            // Gov-Client-Connection-Method (REQUIRED)
            headers["Gov-Client-Connection-Method"] = "DESKTOP_APP_DIRECT"

            // Gov-Client-Device-ID (REQUIRED)
            headers["Gov-Client-Device-ID"] = getOrCreateDeviceId()

            // Gov-Client-User-IDs (REQUIRED)
            headers["Gov-Client-User-IDs"] = getUserIds()

            // Gov-Client-Timezone (REQUIRED)
            headers["Gov-Client-Timezone"] = getTimezone()

            // Gov-Client-Local-IPs (REQUIRED)
            headers["Gov-Client-Local-IPs"] = getLocalIPs()

            // Gov-Client-Local-IPs-Timestamp (REQUIRED) — captured as close as
            // possible to when Gov-Client-Local-IPs itself was collected, above.
            headers["Gov-Client-Local-IPs-Timestamp"] = getLocalIpsTimestamp()

            // Gov-Client-Screens (REQUIRED)
            headers["Gov-Client-Screens"] = getScreenInfo()

            // Gov-Client-Window-Size (REQUIRED)
            headers["Gov-Client-Window-Size"] =
                "width=${context.windowWidth}&height=${context.windowHeight}"

            // Gov-Client-User-Agent (REQUIRED)
            headers["Gov-Client-User-Agent"] = getUserAgent()

            // Gov-Vendor-Version (REQUIRED for vendor software)
            headers["Gov-Vendor-Version"] = getVendorVersion()

            // Gov-Vendor-Product-Name (REQUIRED)
            headers["Gov-Vendor-Product-Name"] = getVendorProductName()

            // Gov-Vendor-License-IDs (REQUIRED — sent with an empty value,
            // since LibreMTD is FOSS and has no license key to report)
            headers["Gov-Vendor-License-IDs"] = getVendorLicenseIds()

            // Gov-Client-Public-IP (RECOMMENDED)
            getPublicIP()?.let { headers["Gov-Client-Public-IP"] = it }

            // Gov-Client-MAC-Addresses (OPTIONAL but recommended)
            headers["Gov-Client-MAC-Addresses"] = getMACAddresses()

        } catch (e: Exception) {
            logger.error(e) { "Error generating fraud prevention headers" }
        }

        return headers
    }

    /**
     * Builds and validates fraud prevention headers.
     * Fails fast if headers are not compliant.
     */
    fun buildValidatedHeaders(context: ClientContext): Map<String, String> {
        val headers = buildHeaders(context)

        require(validateHeaders(headers)) {
            "Invalid fraud prevention headers"
        }

        return headers
    }

    /**
     * Percent-encodes a single key or value per HMRC's fraud prevention spec.
     * URLEncoder encodes spaces as "+", but the spec requires "%20", so we fix that up.
     * Do NOT pass "=" or "&" through this function when they are being used as
     * separators — only encode the key and value either side of them.
     */
    internal fun percentEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /**
     * Joins key-value pairs into the "<key>=<value>&<key2>=<value2>&..." structure
     * HMRC requires for several of these headers, percent-encoding each key and
     * value but leaving the "=" and "&" separators untouched.
     */
    internal fun buildKeyValueHeader(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (key, value) -> "${percentEncode(key)}=${percentEncode(value)}" }

    /**
     * Get or create DeviceId
     */
    private fun getOrCreateDeviceId(): String {
        // Try to read existing global ID
        if (Files.exists(deviceIdFile) && Files.isReadable(deviceIdFile)) {
            return deviceIdFile.readText().trim()
        }

        // Try to create global ID (first user wins)
        if (!Files.exists(deviceIdFile)) {
            try {
                val newId = UUID.randomUUID().toString()
                Files.writeString(
                    deviceIdFile,
                    newId,
                    CREATE, WRITE
                )
                Files.setPosixFilePermissions(
                    deviceIdFile,
                    PosixFilePermissions.fromString("rw-r--r--")
                )
                return newId
            } catch (e: Exception) {
                // Fall through to derived ID
            }
        }

        // Fallback: derive from machine properties
        return getDerivedDeviceId()
    }

    private fun getDerivedDeviceId(): String {
        val osName = System.getProperty("os.name", "Unknown")
        val osVersion = System.getProperty("os.version", "Unknown")
        val hostName = try {
            InetAddress.getLocalHost().hostName
        } catch (e: Exception) {
            "Unknown"
        }

        return UUID.nameUUIDFromBytes(
            "$osName-$osVersion-$hostName".toByteArray()
        ).toString()
    }

    /**
     * Gets user identifiers (OS username), percent-encoded per spec.
     */
    private fun getUserIds(): String {
        val osUsername = System.getProperty("user.name", "unknown")
        return buildKeyValueHeader("os" to osUsername)
    }

    /**
     * Gets timezone in format: UTC+/-HH:MM
     */
    private fun getTimezone(): String {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val offset = now.offset
        return "UTC${offset}"
    }

    /**
     * Gets local IP addresses, comma-separated. Each value is percent-encoded
     * (this matters for IPv6 addresses, whose colons are reserved characters);
     * the comma separators themselves are left unencoded per spec.
     */
    private fun getLocalIPs(): String {
        val ips = mutableListOf<String>()

        try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { !it.isLoopback && it.isUp }
                .flatMap { it.inetAddresses.asSequence() }
                .forEach { addr ->
                    val ip = addr.hostAddress
                    // Filter out IPv6 zone IDs
                    val cleanIp = ip?.split("%")?.get(0)
                    if (cleanIp != null && cleanIp.isNotEmpty()) {
                        ips.add(cleanIp)
                    }
                }
        } catch (e: Exception) {
            logger.error(e) { "Error getting local IP addresses" }
            // Fallback to localhost
            ips.add(InetAddress.getLocalHost().hostAddress)
        }

        val result = if (ips.isEmpty()) listOf("127.0.0.1") else ips
        return result.joinToString(",") { percentEncode(it) }
    }

    /**
     * Gets a UTC timestamp showing when Gov-Client-Local-IPs was collected,
     * in the format HMRC requires: yyyy-MM-ddThh:mm:ss.sssZ
     */
    private fun getLocalIpsTimestamp(): String {
        val formatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC)
        return formatter.format(Instant.now())
    }

    /**
     * Gets public IP address (optional but recommended)
     */
    private fun getPublicIP(): String? {
        // In a real application, you would query an external service
        // For now, return null as it's optional
        return null
    }

    /**
     * Gets MAC addresses of network interfaces, comma-separated. Each value
     * is percent-encoded (the colons in "AA:BB:CC:..." are reserved
     * characters); the comma separators themselves are left unencoded.
     */
    private fun getMACAddresses(): String {
        val macs = mutableListOf<String>()

        try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { !it.isLoopback && it.isUp }
                .forEach { ni ->
                    ni.hardwareAddress?.let { mac ->
                        val macStr = mac.joinToString(":") {
                            String.format("%02X", it)
                        }
                        macs.add(macStr)
                    }
                }
        } catch (e: Exception) {
            logger.error(e) { "Error getting MAC addresses" }
        }

        val result = if (macs.isEmpty()) listOf("00:00:00:00:00:00") else macs
        return result.joinToString(",") { percentEncode(it) }
    }

    /**
     * Gets screen information in format: width=X&height=Y&scaling-factor=Z&colour-depth=D
     */
    private fun getScreenInfo(): String {
        val toolkit = java.awt.Toolkit.getDefaultToolkit()
        val screenSize = toolkit.screenSize
        val resolution = toolkit.screenResolution

        // Calculate scaling factor
        val scalingFactor = resolution / 96.0

        // Assume 24-bit color depth (standard for modern displays)
        val colorDepth = 24

        return "width=${screenSize.width}&height=${screenSize.height}" +
                "&scaling-factor=${"%.2f".format(scalingFactor)}&colour-depth=$colorDepth"
    }

    /**
     * Updates window size header (call this when window is resized)
     */
    fun updateWindowSizeHeader(width: Int, height: Int): String {
        return "width=$width&height=$height"
    }

    /**
     * Maps the JVM's os.name to the coarse OS family HMRC expects
     * (e.g. "Windows", "MacOS", "Linux").
     */
    private fun getOsFamily(): String {
        val osName = System.getProperty("os.name", "Unknown")
        return when {
            osName.startsWith("Windows", ignoreCase = true) -> "Windows"
            osName.contains("Mac", ignoreCase = true)        -> "MacOS"
            osName.contains("Linux", ignoreCase = true)      -> "Linux"
            else -> osName
        }
    }

    /**
     * Best-effort device manufacturer/model, cached once per process since
     * hardware doesn't change mid-run. Both fall back to "" if undetermined
     * — HMRC documents an empty value here as acceptable (an advisory, not
     * a hard error) for machines where this genuinely isn't known.
     */
    private val deviceManufacturer: String by lazy { detectDeviceInfo().first }
    private val deviceModel: String by lazy { detectDeviceInfo().second }

    private fun detectDeviceInfo(): Pair<String, String> {
        val osName = System.getProperty("os.name", "")
        return try {
            when {
                osName.contains("Linux", ignoreCase = true) -> readLinuxDmiInfo()
                // Windows/macOS detection would need to shell out (wmic /
                // Get-CimInstance, or system_profiler) and parse the result.
                // Left unimplemented rather than shipped untested — this is
                // an advisory-only field, so "" is a safe, honest fallback.
                else -> "" to ""
            }
        } catch (e: Exception) {
            logger.debug(e) { "Could not determine device manufacturer/model" }
            "" to ""
        }
    }

    /**
     * Reads the motherboard vendor/model from Linux's DMI/SMBIOS data,
     * exposed read-only under /sys/class/dmi/id/. Even a self-built PC has
     * a motherboard, and its vendor usually populates these fields — so
     * this is meaningful, non-fabricated data, not a guess. Some boards
     * report a placeholder like "To Be Filled By O.E.M." for the product
     * name; that's treated as "unknown" rather than sent verbatim.
     */
    private fun readLinuxDmiInfo(): Pair<String, String> {
        val vendor = readDmiField("/sys/class/dmi/id/sys_vendor")
        val model  = readDmiField("/sys/class/dmi/id/product_name")
            ?.takeUnless { it.contains("O.E.M", ignoreCase = true) || it.contains("To Be Filled", ignoreCase = true) }
        return (vendor ?: "") to (model ?: "")
    }

    private fun readDmiField(path: String): String? =
        try {
            val p = Paths.get(path)
            if (Files.isReadable(p)) Files.readString(p).trim().takeIf { it.isNotBlank() } else null
        } catch (e: Exception) {
            null
        }

    /**
     * Gets Gov-Client-User-Agent as the key-value structure HMRC requires for
     * this connection method: os-family, os-version, device-manufacturer,
     * device-model. See:
     * https://developer.service.hmrc.gov.uk/guides/fraud-prevention/connection-method/desktop-app-direct/
     */
    private fun getUserAgent(): String {
        val osVersion = System.getProperty("os.version", "Unknown")
        return buildKeyValueHeader(
            "os-family" to getOsFamily(),
            "os-version" to osVersion,
            "device-manufacturer" to deviceManufacturer,
            "device-model" to deviceModel
        )
    }

    /**
     * Gets Gov-Vendor-Version: a single "<app-name>=<version>" pair, both
     * percent-encoded, derived from Config so there is one source of truth
     * for the app's version number.
     */
    private fun getVendorVersion(): String =
        buildKeyValueHeader(Config.APP_NAME to Config.VERSION)

    /**
     * Gets Gov-Vendor-Product-Name: the name of the product as marketed to
     * end users, percent-encoded. This is a plain value, not a key-value
     * structure — distinct from Gov-Vendor-Version.
     */
    private fun getVendorProductName(): String =
        percentEncode(Config.APP_NAME)

    /**
     * Gets Gov-Vendor-License-IDs. LibreMTD is FOSS and has no real license
     * key to report — but HMRC's validator treats an empty value here as a
     * hard error ("value is missing" / "value is not hashed"), not merely
     * a warning, contrary to older anecdotal guidance. So this generates a
     * persistent, installation-specific identifier (same pattern as
     * getOrCreateDeviceId() above), hashed with SHA-256 before sending.
     * It doesn't represent a real software license — it exists purely to
     * satisfy the header's technical requirement for a persistent hashed
     * value — but it's stable across runs on the same installation, which
     * is the property HMRC's spec actually asks for.
     */
    private fun getVendorLicenseIds(): String =
        buildKeyValueHeader(Config.APP_NAME to sha256Hex(getOrCreatePseudoLicenseId()))

    private val pseudoLicenseIdFile: Path by lazy { deviceIdFile.resolveSibling("license-id") }

    private fun getOrCreatePseudoLicenseId(): String {
        if (Files.exists(pseudoLicenseIdFile) && Files.isReadable(pseudoLicenseIdFile)) {
            return pseudoLicenseIdFile.readText().trim()
        }

        return try {
            val newId = UUID.randomUUID().toString()
            Files.writeString(pseudoLicenseIdFile, newId, CREATE, WRITE)
            Files.setPosixFilePermissions(pseudoLicenseIdFile, PosixFilePermissions.fromString("rw-r--r--"))
            newId
        } catch (e: Exception) {
            logger.warn(e) { "Could not persist pseudo-license id; using a session-only value" }
            // Falls back to a fresh UUID each call if we can't write to disk at all —
            // not stable across runs, but still satisfies the header's format.
            UUID.randomUUID().toString()
        }
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Validates that all required headers are present
     */
    fun validateHeaders(headers: Map<String, String>): Boolean {
        val requiredHeaders = listOf(
            "Gov-Client-Connection-Method",
            "Gov-Client-Device-ID",
            "Gov-Client-User-IDs",
            "Gov-Client-Timezone",
            "Gov-Client-Local-IPs",
            "Gov-Client-Local-IPs-Timestamp",
            "Gov-Client-Screens",
            "Gov-Client-Window-Size",
            "Gov-Client-User-Agent",
            "Gov-Vendor-Version",
            "Gov-Vendor-Product-Name",
            "Gov-Vendor-License-IDs"
        )

        val missingHeaders = requiredHeaders.filter { !headers.containsKey(it) }

        if (missingHeaders.isNotEmpty()) {
            logger.error { "Missing required fraud prevention headers: $missingHeaders" }
            return false
        }

        return true
    }
}