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

import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.scene.web.WebView
import javafx.stage.Stage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.charlesatkinson.libremtd.security.TokenStore
import org.charlesatkinson.libremtd.ui.components.UiPreferences
import org.charlesatkinson.libremtd.utils.Config
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

private val logger = KotlinLogging.logger {}

// ── OAuth2Handler ─────────────────────────────────────────────────────────────

class OAuth2Handler(
    private val clientId:     String,
    private val clientSecret: String,
    private val prefs:        UiPreferences,
    private val redirectUri:  String  = Config.hmrcRedirectUri,
    private val isSandbox:    Boolean = Config.hmrcSandbox,
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val oauthBaseUrl = if (isSandbox) Config.HMRC_SANDBOX_OAUTH_URL
    else           Config.HMRC_PRODUCTION_OAUTH_URL
    private val authUrl  = "$oauthBaseUrl/oauth/authorize"
    private val tokenUrl = "$oauthBaseUrl/oauth/token"

    // PKCE state — generated fresh for each authorize() call
    private var codeVerifier:  String = ""
    private var codeChallenge: String = ""
    private var state:         String = ""

    // ── Public data types ─────────────────────────────────────────────────────

    @Serializable
    data class TokenResponse(
        @SerialName("access_token")  val accessToken:  String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in")    val expiresIn:    Int,
        @SerialName("token_type")    val tokenType:    String,
        @SerialName("scope")         val scope:        String? = null,
    )

    data class OAuth2Result(
        val accessToken:  String,
        val refreshToken: String?,
        val expiresIn:    Int,
        val scope:        String?,
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Initiates the OAuth2 authorisation flow using PKCE.
     * Opens an embedded browser window for the user to authenticate with HMRC,
     * then exchanges the returned authorisation code for access and refresh tokens.
     * Times out after 5 minutes if the user neither completes nor cancels the flow.
     */
    suspend fun authorize(
        scopes: List<String> = listOf("read:self-assessment", "write:self-assessment"),
    ): OAuth2Result? = withContext(Dispatchers.IO) {
        try {
            generatePKCEParameters()
            val authorizationUrl = buildAuthorizationUrl(scopes)
            logger.info { "Starting OAuth2 authorisation against ${if (isSandbox) "sandbox" else "production"}" }

            val authCode = try {
                withTimeout(5 * 60 * 1_000L) {
                    openBrowserAndWaitForCallback(authorizationUrl)
                }
            } catch (e: TimeoutCancellationException) {
                logger.error { "OAuth2 authorisation timed out after 5 minutes — no response from HMRC" }
                null
            }

            if (authCode != null) {
                exchangeCodeForToken(authCode)
            } else {
                logger.error { "No authorisation code received" }
                null
            }
        } catch (e: Exception) {
            logger.error(e) { "Authorisation failed" }
            null
        }
    }

    /**
     * Returns a valid access token, refreshing automatically if expired.
     * Returns null if no token is available or refresh fails.
     */
    suspend fun getValidToken(userId: Int): String? {
        if (!TokenStore.isExpired()) return TokenStore.getAccessToken()

        logger.info { "Access token expired — attempting refresh" }
        val refreshToken = TokenStore.getRefreshToken() ?: run {
            logger.error { "No refresh token available" }
            return null
        }

        val result = refreshAccessToken(refreshToken) ?: run {
            logger.error { "Token refresh failed" }
            TokenStore.clear(userId)
            return null
        }

        TokenStore.store(result, userId)
        return result.accessToken
    }

    /**
     * Refreshes an expired access token using the stored refresh token.
     */
    suspend fun refreshAccessToken(refreshToken: String): OAuth2Result? =
        withContext(Dispatchers.IO) {
            try {
                val requestBody = formBody(
                    "grant_type"    to "refresh_token",
                    "refresh_token" to refreshToken,
                    "client_id"     to clientId,
                    "client_secret" to clientSecret,
                )

                val response = post(tokenUrl, requestBody)

                if (response.statusCode() == 200) {
                    json.decodeFromString<TokenResponse>(response.body()).toResult()
                } else {
                    logger.error { "Token refresh failed: ${response.statusCode()} — ${response.body()}" }
                    null
                }
            } catch (e: Exception) {
                logger.error(e) { "Token refresh failed" }
                null
            }
        }

    // ── PKCE ─────────────────────────────────────────────────────────────────

    private fun generatePKCEParameters() {
        val secureRandom = SecureRandom()

        // Code verifier: 32 random bytes → base64url (43 chars, within 43–128 range)
        val verifierBytes = ByteArray(32)
        secureRandom.nextBytes(verifierBytes)
        codeVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes)

        // Code challenge: SHA-256 of verifier → base64url
        val challengeBytes = MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.toByteArray(StandardCharsets.UTF_8))
        codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(challengeBytes)

        // State: 16 random bytes → base64url, for CSRF protection
        val stateBytes = ByteArray(16)
        secureRandom.nextBytes(stateBytes)
        state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes)

        logger.info { "PKCE parameters generated" }
    }

    // ── URL building ──────────────────────────────────────────────────────────

    private fun buildAuthorizationUrl(scopes: List<String>): String = buildString {
        append(authUrl)
        append("?response_type=code")
        append("&client_id=${encode(clientId)}")
        append("&redirect_uri=${encode(redirectUri)}")
        append("&scope=${encode(scopes.joinToString(" "))}")
        append("&state=${encode(state)}")
        append("&code_challenge=${encode(codeChallenge)}")
        append("&code_challenge_method=S256")
    }

    // ── Browser callback ──────────────────────────────────────────────────────

    private suspend fun openBrowserAndWaitForCallback(authUrl: String): String? =
        suspendCancellableCoroutine { continuation ->
            Platform.runLater {
                try {
                    val webView = WebView()
                    val stage   = Stage()
                    stage.title = "HMRC Authorisation"

                    // Set application icon so the window appears correctly in the
                    // task switcher and panel rather than showing the default icon.
                    // Multiple sizes are added; the platform picks the most suitable.
                    listOf(
                        "icons/libremtd-16.png",
                        "icons/libremtd-32.png",
                        "icons/libremtd-48.png",
                        "icons/libremtd-128.png",
                        "icons/libremtd-256.png",
                    ).mapNotNull { path ->
                        OAuth2Handler::class.java.classLoader
                            .getResourceAsStream(path)
                            ?.let { stream ->
                                try { Image(stream) }
                                catch (e: Exception) {
                                    logger.warn { "Could not load icon $path: ${e.message}" }
                                    null
                                }
                            }
                    }.also { icons ->
                        if (icons.isEmpty()) logger.warn { "No application icons loaded for OAuth2 window" }
                        else stage.icons.addAll(icons)
                    }

                    // Recall last window size; default to 800 × 900 if not yet saved.
                    val initWidth  = prefs.oauthWindowWidth  ?: 800.0
                    val initHeight = prefs.oauthWindowHeight ?: 900.0
                    stage.scene = Scene(webView, initWidth, initHeight)

                    // Restore last window position, if saved.
                    prefs.oauthWindowX?.let { stage.x = it }
                    prefs.oauthWindowY?.let { stage.y = it }

                    // Guard against the location listener firing multiple times.
                    var resumed = false

                    fun saveGeometry() {
                        prefs.oauthWindowX      = stage.x
                        prefs.oauthWindowY      = stage.y
                        prefs.oauthWindowWidth  = stage.width
                        prefs.oauthWindowHeight = stage.height
                    }

                    // Close the stage if the coroutine is cancelled externally
                    // (e.g. the user navigates away in the UI before completing auth).
                    continuation.invokeOnCancellation {
                        Platform.runLater {
                            if (!resumed) {
                                resumed = true
                                logger.info { "OAuth2 authorisation cancelled externally — closing browser window" }
                                saveGeometry()
                                stage.close()
                            }
                        }
                    }

                    webView.engine.locationProperty().addListener { _, _, newLocation ->
                        if (newLocation.startsWith(redirectUri) && !resumed) {
                            resumed = true
                            try {
                                val uri    = URI.create(newLocation)
                                val params = uri.query
                                    ?.split("&")
                                    ?.associate {
                                        val parts = it.split("=", limit = 2)
                                        parts[0] to (parts.getOrNull(1) ?: "")
                                    }
                                    ?: emptyMap()

                                val code          = params["code"]
                                val returnedState = params["state"]

                                Platform.runLater {
                                    saveGeometry()
                                    stage.close()
                                }

                                if (returnedState == state && code != null) {
                                    logger.info { "Authorisation code received" }
                                    continuation.resumeWith(Result.success(code))
                                } else {
                                    logger.error { "State mismatch or missing code" }
                                    continuation.resumeWith(Result.success(null))
                                }
                            } catch (e: Exception) {
                                logger.error(e) { "Error parsing OAuth callback" }
                                Platform.runLater {
                                    saveGeometry()
                                    stage.close()
                                }
                                continuation.resumeWith(Result.success(null))
                            }
                        }
                    }

                    stage.setOnCloseRequest {
                        if (!resumed) {
                            resumed = true
                            logger.info { "Authorisation window closed by user" }
                            saveGeometry()
                            continuation.resumeWith(Result.success(null))
                        }
                    }

                    webView.engine.load(authUrl)
                    stage.show()
                    logger.info { "OAuth2 browser window shown" }

                } catch (e: Throwable) {
                    logger.error(e) { "Failed to open OAuth2 browser window" }
                    continuation.resumeWith(Result.success(null))
                }
            }
        }

    // ── Token exchange ────────────────────────────────────────────────────────

    private suspend fun exchangeCodeForToken(code: String): OAuth2Result? {
        return try {
            val requestBody = formBody(
                "grant_type"    to "authorization_code",
                "code"          to code,
                "redirect_uri"  to redirectUri,
                "client_id"     to clientId,
                "client_secret" to clientSecret,
                "code_verifier" to codeVerifier,
            )

            val response = post(tokenUrl, requestBody)

            if (response.statusCode() == 200) {
                val result = json.decodeFromString<TokenResponse>(response.body()).toResult()
                logger.info { "Access token obtained successfully" }
                result
            } else {
                logger.error { "Token exchange failed: ${response.statusCode()} — ${response.body()}" }
                null
            }
        } catch (e: Exception) {
            logger.error(e) { "Token exchange failed" }
            null
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun post(url: String, body: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun formBody(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> "$k=${encode(v)}" }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun TokenResponse.toResult() = OAuth2Result(
        accessToken  = accessToken,
        refreshToken = refreshToken,
        expiresIn    = expiresIn,
        scope        = scope,
    )
}