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

package org.charlesatkinson.libremtd.database.tables

import org.jetbrains.exposed.sql.Table

object IncomePropertyEntries : Table("income_property_entries") {
    val id              = integer("id").autoIncrement()
    val periodId        = integer("period_id").references(Periods.id)
    val userId          = integer("user_id").references(Users.id)
    val propertyId      = integer("property_id").references(Properties.id)
    val category        = text("category")
    val amount          = double("amount")
    val description     = text("description")
    val transactionDate = text("transaction_date")
    val recordedAt      = text("recorded_at")
    val supersededAt    = text("superseded_at").nullable()

    override val primaryKey = PrimaryKey(id)
}