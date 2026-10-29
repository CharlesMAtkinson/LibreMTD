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
import org.jetbrains.exposed.sql.javatime.datetime

object Users : Table("libremtd_users") {
    val id = integer("id").autoIncrement()
    val username = varchar("username", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 60)

    // HMRC OAuth tokens
    val hmrcAccessToken = text("hmrc_access_token").nullable()
    val hmrcRefreshToken = text("hmrc_refresh_token").nullable()
    val tokenExpiry = datetime("token_expiry").nullable()

    val createdAt = datetime("created_at")
    val lastLogin = datetime("last_login").nullable()

    override val primaryKey = PrimaryKey(id)
}