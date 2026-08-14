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

package org.charlesatkinson.libremtd.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.charlesatkinson.libremtd.security.FraudPreventionHeaders
import org.charlesatkinson.libremtd.security.OAuth2Handler
import org.charlesatkinson.libremtd.utils.Config
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val logger = KotlinLogging.logger {}

class HmrcApiClient(
    private val libreMtdUserId: Int,
    private val isSandbox:      Boolean = Config.hmrcSandbox,
    private val oauth2Handler:  OAuth2Handler,
    private val fraudHeaders:   FraudPreventionHeaders,
) {
    private val baseUrl = if (isSandbox) Config.HMRC_SANDBOX_URL
    else           Config.HMRC_PRODUCTION_URL

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    /**
     * Builds the fraud prevention headers for [context] and adds them to
     * [builder]. At DEBUG log level, also logs them as ready-to-paste
     * curl -H flags, so they can be tried directly against HMRC's Test
     * Fraud Prevention Headers API:
     * https://developer.service.hmrc.gov.uk/guides/fraud-prevention/test-api/
     *
     * Enable via logback.xml, e.g.:
     *   <logger name="org.charlesatkinson.libremtd.network.HmrcApiClient" level="DEBUG"/>
     *
     * Note: that validate endpoint needs an application-restricted bearer
     * token (client_credentials grant), not the user-restricted token this
     * class uses for real API calls — get that token separately.
     */
    private fun applyFraudPreventionHeaders(builder: HttpRequest.Builder, context: ClientContext) {
        val headers = fraudHeaders.buildHeaders(context)

        logger.debug {
            val curlFlags = headers.entries.joinToString(" \\\n  ") { (name, value) ->
                "-H \"$name: $value\""
            }
            "Fraud prevention headers (paste into curl):\n$curlFlags"
        }

        headers.forEach { (name, value) -> builder.header(name, value) }
    }

    /**
     * Makes an authenticated GET request to the HMRC API.
     * Automatically refreshes the token if expired.
     * Returns the response body as a String, or null on failure.
     */
    suspend fun get(
        path:    String,
        params:  Map<String, String> = emptyMap(),
        context: ClientContext,
        version: String = "2.0",
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse<String>? = withContext(Dispatchers.IO) {
        val token = oauth2Handler.getValidToken(libreMtdUserId) ?: run {
            logger.error { "No valid token available for API call to $path" }
            return@withContext null
        }

        val query = if (params.isEmpty()) ""
        else "?" + params.entries.joinToString("&") { (k, v) -> "$k=$v" }

        val builder = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path$query"))
            .header("Authorization",  "Bearer $token")
            .header("Accept",         "application/vnd.hmrc.$version+json")
            .header("Content-Type",   "application/json")
            .GET()
        logger.info { "GET request to: $baseUrl$path$query with version $version" }

        // Add fraud prevention headers
        applyFraudPreventionHeaders(builder, context)

        if (extraHeaders.isNotEmpty()) {
            logger.info { "Extra headers: $extraHeaders" }
        }
        extraHeaders.forEach { (name, value) ->
            builder.header(name, value)
        }

        try {
            val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            logger.info { "GET $path → ${response.statusCode()}" }
            response
        } catch (e: Exception) {
            logger.error(e) { "GET $path failed" }
            null
        }
    }

    // TODO: A cleaner design would accept an optional queryParams: Map<String, String> argument and build the
    // URI using a URIBuilder or similar, keeping path and query string separate
    suspend fun post(
        path:    String,
        body:    String,
        context: ClientContext,
        version: String = "8.0",
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse<String>? = withContext(Dispatchers.IO) {
        val token = oauth2Handler.getValidToken(libreMtdUserId) ?: run {
            logger.error { "No valid token available for POST $path" }
            return@withContext null
        }

        val builder = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header("Authorization", "Bearer $token")
            .header("Accept",        "application/vnd.hmrc.$version+json")
            .header("Content-Type",  "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        logger.info { "POST request to: $baseUrl$path with version $version" }

        applyFraudPreventionHeaders(builder, context)

        extraHeaders.forEach { (name, value) ->
            builder.header(name, value)
        }

        try {
            val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            logger.info { "POST $path → ${response.statusCode()}" }
            if (response.statusCode() !in listOf(200, 201, 202, 204)) {
                logger.warn { "POST $path non-success body: ${response.body()}" }
            }
            response
        } catch (e: Exception) {
            logger.error(e) { "POST $path failed" }
            null
        }
    }

    suspend fun put(
        path:    String,
        body:    String,
        context: ClientContext,
        version: String = "6.0",
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse<String>? = withContext(Dispatchers.IO) {
        val token = oauth2Handler.getValidToken(libreMtdUserId) ?: run {
            logger.error { "No valid token available for PUT $path" }
            return@withContext null
        }

        val builder = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header("Authorization", "Bearer $token")
            .header("Accept",        "application/vnd.hmrc.$version+json")
            .header("Content-Type",  "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
        logger.info { "PUT request to: $baseUrl$path with version $version" }

        // Add fraud prevention headers
        applyFraudPreventionHeaders(builder, context)

        extraHeaders.forEach { (name, value) ->
            builder.header(name, value)
        }

        try {
            val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            logger.info { "PUT $path → ${response.statusCode()}" }
            if (response.statusCode() != 204) {
                logger.info { "Response body: ${response.body()}" }
            }
            response
        } catch (e: Exception) {
            logger.error(e) { "PUT $path failed" }
            null
        }
    }
}