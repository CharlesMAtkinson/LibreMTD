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

import mu.KotlinLogging
import java.util.Properties

/**
 * Application-wide configuration.
 * Override values via environment variables or JVM system properties.
 * Set HMRC_SANDBOX=false when deploying against HMRC production APIs.
 * See: https://developer.service.hmrc.gov.uk/api-documentation/docs/testing
 */
object Config {
    private val logger = KotlinLogging.logger {}

    const val APP_NAME = "LibreMTD"

    /**
     * Loaded once from /build.properties on the classpath, which Gradle
     * generates at build time (see processResources in build.gradle.kts)
     * by filtering resources/build.properties with the project's real
     * `version` property. This is the single source of truth for the
     * app's version — nothing else should hardcode it separately.
     */
    private val buildProperties: Properties by lazy {
        val props = Properties()
        try {
            // NOTE: must use Config::class.java explicitly here, not a bare
            // "javaClass" inside a Properties().apply{} block — inside apply,
            // the receiver is the Properties instance itself, so an
            // unqualified javaClass would resolve to Properties::class.java
            // (a core JDK class) rather than Config's, silently searching
            // the wrong classloader and always finding nothing.
            val stream = Config::class.java.getResourceAsStream("/build.properties")
            if (stream != null) {
                stream.use { props.load(it) }
            } else {
                logger.warn { "build.properties not found on the classpath; VERSION will fall back to a placeholder" }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Could not load build.properties; VERSION will fall back to a placeholder" }
        }
        props
    }

    /**
     * The app's version, taken from the Gradle project version via
     * build.properties. Falls back to a clearly-labelled placeholder if
     * build.properties couldn't be loaded (e.g. running from a raw class
     * directory that hasn't been through processResources).
     */
    val VERSION: String by lazy {
        buildProperties.getProperty("version")?.takeIf { it.isNotBlank() } ?: "0.0.0-unbuilt"
    }

    /** The date this build was produced, from the same generated properties file. */
    val BUILD_DATE: String by lazy {
        buildProperties.getProperty("buildDate")?.takeIf { it.isNotBlank() } ?: "unknown"
    }

    // ── HMRC API ──────────────────────────────────────────────────────────────
    const val HMRC_SANDBOX_URL    = "https://test-api.service.hmrc.gov.uk"
    const val HMRC_PRODUCTION_URL = "https://api.service.hmrc.gov.uk"

    // OAuth URLs differ from the API base — separate subdomain for the auth UI
    const val HMRC_SANDBOX_OAUTH_URL    = "https://test-api.service.hmrc.gov.uk"
    const val HMRC_PRODUCTION_OAUTH_URL = "https://api.service.hmrc.gov.uk"

    // Default hmrcSandbox to true when envar HMRC_SANDBOX not set (safe)
    val hmrcSandbox: Boolean by lazy {
        System.getenv("HMRC_SANDBOX")?.toBoolean() ?: true
    }

    /** Base URL for all HMRC REST API calls. */
    val hmrcApiBaseUrl: String get() = if (hmrcSandbox) HMRC_SANDBOX_URL else HMRC_PRODUCTION_URL

    /** Base URL for OAuth2 authorisation and token endpoints. */
    val hmrcOAuthBaseUrl: String get() = if (hmrcSandbox) HMRC_SANDBOX_OAUTH_URL else HMRC_PRODUCTION_OAUTH_URL

    val hmrcRedirectUri: String by lazy {
        System.getenv("HMRC_REDIRECT_URI") ?: "http://localhost:8080/oauth/callback"
    }

    // ── Developer / test helpers ──────────────────────────────────────────────
    private fun prop(name: String): String? =
        System.getProperty(name) ?: System.getenv(name)

    val devMode:     Boolean = prop("DEV_MODE")?.toBoolean() ?: false
    val devUsername: String  = prop("DEV_USERNAME") ?: ""
    val devPassword: String  = prop("DEV_PASSWORD") ?: ""

    // ── Sandbox Gov-Test-Scenario overrides ───────────────────────────────────
    //
    // These are only consulted when hmrcSandbox is true.
    // Set via IntelliJ run configuration > Environment variables, or shell export.
    // Leave unset (or set to empty string) for DEFAULT sandbox behaviour.
    //
    // Obligations API scenarios (GET /obligations/details/{nino}/income-and-expenditure):
    //   FULFILLED, OPEN, DYNAMIC, CUMULATIVE, NOT_FOUND, NO_OBLIGATIONS_FOUND, INSOLVENT_TRADER
    //
    // Individual Calculations API scenarios (POST …/trigger/{calculationType}):
    //   DEFAULT, SUBMISSION_FAILED, TAX_YEAR_NOT_SUPPORTED, …
    //
    // Individual Calculations API scenarios (POST …/{calculationId}/crystallisation):
    //   DEFAULT, OUTSIDE_AMENDMENT_WINDOW, FINAL_DECLARATION_IN_PROGRESS,
    //   FINAL_DECLARATION_RECEIVED, FINAL_DECLARATION_TAX_YEAR, INCOME_SOURCES_CHANGED,
    //   INCOME_SOURCES_INVALID, NO_INCOME_SUBMISSIONS_EXIST, RECENT_SUBMISSIONS_EXIST,
    //   RESIDENCY_CHANGED, SUBMISSION_FAILED, TAX_YEAR_NOT_SUPPORTED, NOT_FOUND
    //
    // BSAS API scenarios (GET …/adjustable-summary/{nino}/uk-property/{calculationId}/{taxYear}):
    //   UK_PROPERTY_PROFIT, UK_PROPERTY_LOSS, UK_PROPERTY_ZERO_ADJUSTMENTS (2024-25 onwards),
    //   UK_PROPERTY_STATUS_INVALID, UK_PROPERTY_STATUS_SUPERSEDED, NOT_UK_PROPERTY,
    //   REQUEST_CANNOT_BE_FULFILLED, TAX_YEAR_NOT_SUPPORTED, STATEFUL
    //   Leave unset for DEFAULT (simulates no data found).

    /** Gov-Test-Scenario for the obligations fetch. Null → DEFAULT. */
    val sandboxObligationsScenario: String? by lazy {
        System.getenv("HMRC_SANDBOX_OBLIGATIONS_SCENARIO")?.takeIf { it.isNotBlank() }
    }

    /**
     * Gov-Test-Scenario for the intent-to-finalise calculation trigger and fetch.
     * Null → DEFAULT.
     */
    val sandboxCalcScenario: String? by lazy {
        System.getenv("HMRC_SANDBOX_CALC_SCENARIO")?.takeIf { it.isNotBlank() }
    }

    /**
     * Gov-Test-Scenario for the UK property BSAS GET
     * (Retrieve a UK Property Business Source Adjustable Summary).
     * Null → DEFAULT (simulates no data found; BSAS fetch fails gracefully
     * and the user proceeds to final declaration without a UK adjustment step).
     * Useful values:
     *   UK_PROPERTY_PROFIT  — success response with profit figures
     *   UK_PROPERTY_LOSS    — success response with loss figures
     */
    val sandboxBsasUkPropGetScenario: String? by lazy {
        System.getenv("HMRC_SANDBOX_BSAS_UKPROP_GET_SCENARIO")?.takeIf { it.isNotBlank() }
    }

    /**
     * Gov-Test-Scenario for the UK property BSAS POST
     * (Submit UK Property Accounting Adjustments).
     * Null → DEFAULT (sandbox accepts the submission and returns 200).
     * The POST endpoint has a separate scenario table from the GET;
     * UK_PROPERTY_PROFIT is not valid here.
     */
    val sandboxBsasUkPropPostScenario: String? by lazy {
        System.getenv("HMRC_SANDBOX_BSAS_UKPROP_POST_SCENARIO")?.takeIf { it.isNotBlank() }
    }

    /**
     * Gov-Test-Scenario for the foreign property BSAS GET
     * (Retrieve a Foreign Property Business Source Adjustable Summary).
     * Null → DEFAULT (simulates no data found; BSAS fetch fails gracefully
     * and the user proceeds to final declaration without a foreign adjustment step).
     * Useful values:
     *   FOREIGN_PROPERTY_PROFIT  — success response with profit figures
     *   FOREIGN_PROPERTY_LOSS    — success response with loss figures
     * Full scenario table:
     * https://developer.service.hmrc.gov.uk/api-documentation/docs/api/service/self-assessment-bsas-api/7.0/oas/page#tag/Foreign-property-business
     */
    val sandboxBsasForeignPropGetScenario: String? by lazy {
        System.getenv("HMRC_SANDBOX_BSAS_FOREIGNPROP_GET_SCENARIO")?.takeIf { it.isNotBlank() }
    }

    /**
     * Gov-Test-Scenario for the foreign property BSAS POST
     * (Submit Foreign Property Accounting Adjustments).
     * Null → DEFAULT (sandbox accepts the submission and returns 200).
     * The POST endpoint has a separate scenario table from the GET;
     * FOREIGN_PROPERTY_PROFIT is not valid here.
     */
    val sandboxBsasForeignPropPostScenario: String? by lazy {
        System.getenv("HMRC_SANDBOX_BSAS_FOREIGNPROP_POST_SCENARIO")?.takeIf { it.isNotBlank() }
    }

    /**
     * Gov-Test-Scenario for the final declaration POST (crystallisation).
     * Null → DEFAULT (simulates success, returns 204).
     */
    val sandboxFinalDeclScenario: String? by lazy {
        System.getenv("HMRC_SANDBOX_FINAL_DECL_SCENARIO")?.takeIf { it.isNotBlank() }
    }

}