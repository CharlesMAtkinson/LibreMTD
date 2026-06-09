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

object Submissions : Table("submissions") {
    val id             = integer("id").autoIncrement()
    val userId         = integer("user_id").references(Users.id)
    val periodId       = integer("period_id").references(Periods.id).nullable()
    val submissionType = text("submission_type")
    val submittedAt    = text("submitted_at")
    val hmrcResponse   = text("hmrc_response")

    override val primaryKey = PrimaryKey(id)
}
