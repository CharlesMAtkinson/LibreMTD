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
 * Application-wide configuration.
 * Override values via environment variables or JVM system properties.
 * Set HMRC_SANDBOX=false when deploying against HMRC production APIs.
 * See: https://developer.service.hmrc.gov.uk/api-documentation/docs/testing
 */
object Config {
    const val VERSION  = "1.0.0-SNAPSHOT"
    const val APP_NAME = "LibreMTD"

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
     * Gov-Test-Scenario for the final declaration POST (crystallisation).
     * Null → DEFAULT (simulates success, returns 204).
     */
    val sandboxFinalDeclScenario: String? by lazy {
        System.getenv("HMRC_SANDBOX_FINAL_DECL_SCENARIO")?.takeIf { it.isNotBlank() }
    }
}
