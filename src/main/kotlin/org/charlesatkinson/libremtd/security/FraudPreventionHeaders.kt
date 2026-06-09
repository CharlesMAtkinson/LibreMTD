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
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.*
import kotlin.io.path.readText
import org.charlesatkinson.libremtd.network.ClientContext

/**
 * Generates fraud prevention headers required by HMRC Making Tax Digital API
 * See: https://developer.service.hmrc.gov.uk/guides/fraud-prevention/
 */
class FraudPreventionHeaders {
    private val logger = KotlinLogging.logger {}

    companion object {
        private const val APP_VERSION = "1.0.0"
        private const val APP_NAME = "LibreMTD"
        private const val VENDOR_VERSION = "1.0.0"
    }

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

            // Gov-Client-Screens (REQUIRED)
            headers["Gov-Client-Screens"] = getScreenInfo()

            // Gov-Client-Window-Size (REQUIRED)
            headers["Gov-Client-Window-Size"] =
			    "width=${context.windowWidth}&height=${context.windowHeight}"

            // Gov-Client-User-Agent (REQUIRED)
            headers["Gov-Client-User-Agent"] = getUserAgent()

            // Gov-Vendor-Version (REQUIRED for vendor software)
            headers["Gov-Vendor-Version"] = getVendorVersion()

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
     * Gets user identifiers (OS username)
     */
    private fun getUserIds(): String {
        val osUsername = System.getProperty("user.name", "unknown")
        return "os=$osUsername"
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
     * Gets local IP addresses
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

        return if (ips.isEmpty()) "127.0.0.1" else ips.joinToString(",")
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
     * Gets MAC addresses of network interfaces
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

        return if (macs.isEmpty()) "00:00:00:00:00:00" else macs.joinToString(",")
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
     * Gets current window size in format: width=X&height=Y
     */
    private fun getWindowSize(): String {
        // This should be updated with actual window dimensions
        // For now, using screen dimensions as placeholder
        val toolkit = java.awt.Toolkit.getDefaultToolkit()
        val screenSize = toolkit.screenSize
        return "width=${screenSize.width}&height=${screenSize.height}"
    }

    /**
     * Updates window size header (call this when window is resized)
     */
    fun updateWindowSizeHeader(width: Int, height: Int): String {
        return "width=$width&height=$height"
    }

    /**
     * Gets user agent string
     */
    private fun getUserAgent(): String {
        val osName = System.getProperty("os.name", "Unknown")
        val osVersion = System.getProperty("os.version", "Unknown")
        val osArch = System.getProperty("os.arch", "Unknown")
        val javaVersion = System.getProperty("java.version", "Unknown")

        return "LibreMTD/$APP_VERSION (${osName} ${osVersion}; ${osArch}; Java ${javaVersion})"
    }

    /**
     * Gets vendor version information
     */
    private fun getVendorVersion(): String {
        return "$APP_NAME=$VENDOR_VERSION"
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
            "Gov-Client-Screens",
            "Gov-Client-Window-Size",
            "Gov-Client-User-Agent",
            "Gov-Vendor-Version"
        )

        val missingHeaders = requiredHeaders.filter { !headers.containsKey(it) }

        if (missingHeaders.isNotEmpty()) {
            logger.error { "Missing required fraud prevention headers: $missingHeaders" }
            return false
        }

        return true
    }
}
