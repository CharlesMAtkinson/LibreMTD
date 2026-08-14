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

package org.charlesatkinson.libremtd.api.models

import kotlinx.serialization.Serializable

@Serializable
data class BsasMetadata(
    val calculationId: String,
    val requestedDateTime: String? = null,
    val adjustedDateTime: String? = null,
    val nino: String,
    val taxYear: String,
    val summaryStatus: String? = null,
)

@Serializable
data class BsasIncomeBreakdown(
    val totalRentsReceived: Double? = null,
    val premiumsOfLeaseGrant: Double? = null,
    val reversePremiums: Double? = null,
    val otherPropertyIncome: Double? = null,
    val rarRentReceived: Double? = null,
)

@Serializable
data class BsasExpensesBreakdown(
    val premisesRunningCosts: Double? = null,
    val repairsAndMaintenance: Double? = null,
    val financialCosts: Double? = null,
    val professionalFees: Double? = null,
    val costOfServices: Double? = null,
    val travelCosts: Double? = null,
    val residentialFinancialCost: Double? = null,
    val broughtFwdResidentialFinancialCost: Double? = null,
    val other: Double? = null,
)

@Serializable
data class BsasAdditions(
    val privateUseAdjustment: Double? = null,
    val balancingCharge: Double? = null,
    val bpraBalancingCharge: Double? = null,
)

@Serializable
data class BsasDeductions(
    val zeroEmissionGoods: Double? = null,
    val annualInvestmentAllowance: Double? = null,
    val costOfReplacingDomesticItems: Double? = null,
    val businessPremisesRenovationAllowance: Double? = null,
    val otherCapitalAllowance: Double? = null,
    val rarReliefClaimed: Double? = null,
    val structuredBuildingAllowance: Double? = null,
    val enhancedStructuredBuildingAllowance: Double? = null,
    val zeroEmissionsCarAllowance: Double? = null,
)

@Serializable
data class BsasSummaryCalculation(
    val totalIncome: Double? = null,
    val income: BsasIncomeBreakdown? = null,
    val totalExpenses: Double? = null,
    val expenses: BsasExpensesBreakdown? = null,
    val netProfit: Double? = null,
    val totalAdditions: Double? = null,
    val additions: BsasAdditions? = null,
    val totalDeductions: Double? = null,
    val deductions: BsasDeductions? = null,
    val taxableProfit: Double? = null,
)

@Serializable
data class BsasAdjustmentsIncome(
    val totalRentsReceived: Double? = null,
    val premiumsOfLeaseGrant: Double? = null,
    val reversePremiums: Double? = null,
    val otherPropertyIncome: Double? = null,
)

@Serializable
data class BsasAdjustmentsExpenses(
    val premisesRunningCosts: Double? = null,
    val repairsAndMaintenance: Double? = null,
    val financialCosts: Double? = null,
    val professionalFees: Double? = null,
    val costOfServices: Double? = null,
    val residentialFinancialCost: Double? = null,
    val other: Double? = null,
    val travelCosts: Double? = null,
)

@Serializable
data class BsasAdjustments(
    val zeroAdjustments: Boolean? = null,
    val income: BsasAdjustmentsIncome? = null,
    val expenses: BsasAdjustmentsExpenses? = null,
)

@Serializable
data class BsasInputs(
    val businessId: String? = null,
    val businessName: String? = null,
    val accountingPeriodStartDate: String? = null,
    val accountingPeriodEndDate: String? = null,
    val source: String? = null,
)

@Serializable
data class UkPropertyBsasResponse(
    val metadata: BsasMetadata,
    val inputs: BsasInputs? = null,
    val adjustableSummaryCalculation: BsasSummaryCalculation? = null,
    val adjustments: BsasAdjustments? = null,
    val adjustedSummaryCalculation: BsasSummaryCalculation? = null,
)

// ── POST request models ───────────────────────────────────────────────────────

@Serializable
data class BsasPostIncome(
    val totalRentsReceived: Double? = null,
    val premiumsOfLeaseGrant: Double? = null,
    val reversePremiums: Double? = null,
    val otherPropertyIncome: Double? = null,
)

@Serializable
data class BsasPostExpenses(
    val premisesRunningCosts: Double? = null,
    val repairsAndMaintenance: Double? = null,
    val financialCosts: Double? = null,
    val professionalFees: Double? = null,
    val costOfServices: Double? = null,
    val residentialFinancialCost: Double? = null,
    val other: Double? = null,
    val travelCosts: Double? = null,
)

sealed class BsasPostPayload {
    object Zero : BsasPostPayload()
    data class Adjustments(
        val income: BsasPostIncome? = null,
        val expenses: BsasPostExpenses? = null,
    ) : BsasPostPayload()
}

// ── Foreign property BSAS GET response ───────────────────────────────────────

@Serializable
data class ForeignPropertyBsasIncome(
    val totalRentsReceived:   Double? = null,
    val premiumsOfLeaseGrant: Double? = null,
    val otherPropertyIncome:  Double? = null,
)

@Serializable
data class ForeignPropertyBsasExpenses(
    val premisesRunningCosts:     Double? = null,
    val repairsAndMaintenance:    Double? = null,
    val financialCosts:           Double? = null,
    val professionalFees:         Double? = null,
    val costOfServices:           Double? = null,
    val travelCosts:              Double? = null,
    val residentialFinancialCost: Double? = null,
    val other:                    Double? = null,
    // broughtFwdResidentialFinancialCost omitted: HMRC docs state it is not
    // currently in use, will not be returned in a live environment, and will
    // be removed in the near future.
)

@Serializable
data class ForeignPropertyBsasSummaryCalculation(
    val totalIncome:        Double? = null,
    val income:             ForeignPropertyBsasIncome? = null,
    val totalExpenses:      Double? = null,
    val expenses:           ForeignPropertyBsasExpenses? = null,
    val netProfit:          Double? = null,
    val taxableProfit:      Double? = null,
    val countryLevelDetail: List<ForeignPropertyBsasCountryCalculation>? = null,
)

@Serializable
data class ForeignPropertyBsasCountryCalculation(
    val countryCode:   String,
    val totalIncome:   Double? = null,
    val totalExpenses: Double? = null,
    val netProfit:     Double? = null,
    val taxableProfit: Double? = null,
    val income:        ForeignPropertyBsasIncome? = null,
    val expenses:      ForeignPropertyBsasExpenses? = null,
)

@Serializable
data class ForeignPropertyBsasCountryAdjustment(
    val countryCode: String,
    val income:      ForeignPropertyBsasIncome? = null,
    val expenses:    ForeignPropertyBsasExpenses? = null,
)

// 2025-26 shape: adjustments keyed by countryLevelDetail.
// 2026-27 changes this to propertyLevelDetail — model will need extending then.
@Serializable
data class ForeignPropertyBsasAdjustments(
    val countryLevelDetail: List<ForeignPropertyBsasCountryAdjustment>? = null,
)

@Serializable
data class ForeignPropertyBsasResponse(
    val metadata:                     BsasMetadata,
    val inputs:                       BsasInputs? = null,
    val adjustableSummaryCalculation: ForeignPropertyBsasSummaryCalculation? = null,
    val adjustments:                  ForeignPropertyBsasAdjustments? = null,
)

// ── Foreign property BSAS POST payload ───────────────────────────────────────

// Income and expense field sets are identical to the GET response types,
// so we reuse ForeignPropertyBsasIncome and ForeignPropertyBsasExpenses
// for the POST body rather than duplicating them.

data class ForeignPropertyBsasPostCountry(
    val countryCode: String,
    val income:      ForeignPropertyBsasIncome? = null,
    val expenses:    ForeignPropertyBsasExpenses? = null,
)

data class ForeignPropertyBsasPostPayload(
    val countryLevelDetail: List<ForeignPropertyBsasPostCountry>,
)