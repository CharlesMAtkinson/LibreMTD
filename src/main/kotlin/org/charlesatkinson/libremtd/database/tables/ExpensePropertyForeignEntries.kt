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

package org.charlesatkinson.libremtd.database.tables

import org.jetbrains.exposed.sql.Table

/**
 * Dated expense entries for foreign property letting.
 * category values correspond to ForeignExpenseCategory.dbKey in the UI layer
 * (PremisesRunningCosts, RepairsAndMaintenance, FinancialCosts,
 * ProfessionalFees, TravelCosts, CostOfServices, ResidentialFinancialCost,
 * BroughtFwdResidentialFinancialCost, Other).
 *
 * Note: the foreign cumulative endpoint has no consolidatedExpenses option.
 */
object ExpensePropertyForeignEntries : Table("expense_property_foreign_entries") {
    val id              = integer("id").autoIncrement()
    val periodId        = integer("period_id").references(Periods.id)
    val userId          = integer("user_id").references(Users.id)
    val propertyId      = integer("property_id").references(Properties.id)
    val category        = varchar("category", 64)
    val amount          = double("amount")
    val description     = varchar("description", 255)
    val transactionDate = varchar("transaction_date", 10)
    val recordedAt      = varchar("recorded_at", 32)
    val supersededAt    = varchar("superseded_at", 32).nullable()

    override val primaryKey = PrimaryKey(id)
}