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

package org.charlesatkinson.libremtd.database

import mu.KotlinLogging
import org.charlesatkinson.libremtd.database.tables.AllowanceEntries
import org.charlesatkinson.libremtd.database.tables.ExpensePropertyForeignEntries
import org.charlesatkinson.libremtd.database.tables.ExpensePropertyUkEntries
import org.charlesatkinson.libremtd.database.tables.ForeignPropertyElections
import org.charlesatkinson.libremtd.database.tables.ForeignPropertySubmissionElections
import org.charlesatkinson.libremtd.database.tables.IncomeDividendEntries
import org.charlesatkinson.libremtd.database.tables.IncomePropertyForeignEntries
import org.charlesatkinson.libremtd.database.tables.IncomePropertyUkEntries
import org.charlesatkinson.libremtd.database.tables.IncomeSavingsEntries
import org.charlesatkinson.libremtd.database.tables.Periods
import org.charlesatkinson.libremtd.database.tables.Properties
import org.charlesatkinson.libremtd.database.tables.Submissions
import org.charlesatkinson.libremtd.database.tables.Users
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path
import org.jetbrains.exposed.sql.Database as ExposedDatabase
import org.charlesatkinson.libremtd.database.tables.HmrcSettings as HmrcSettingsTable
import org.charlesatkinson.libremtd.database.tables.IncomeDividendForeignEntries
import org.charlesatkinson.libremtd.database.tables.UiPreferences

private val logger = KotlinLogging.logger {}
private lateinit var db: ExposedDatabase

object Database {

    fun init(dbPath: Path) {
        logger.info { "Database path: ${dbPath.toAbsolutePath()}" }

        db = ExposedDatabase.connect(
            url = "jdbc:sqlite:${dbPath.toAbsolutePath()}",
            driver = "org.sqlite.JDBC"
        )

        // Exposed schema
        transaction {
            SchemaUtils.create(
                AllowanceEntries,
                ExpensePropertyForeignEntries,
                ExpensePropertyUkEntries,
                ForeignPropertyElections,
                ForeignPropertySubmissionElections,
                HmrcSettingsTable,
                IncomeDividendEntries,
                IncomeDividendForeignEntries,
                IncomePropertyForeignEntries,
                IncomePropertyUkEntries,
                IncomeSavingsEntries,
                Periods,
                Properties,
                Submissions,
                UiPreferences,
                Users,
            )
            logger.info { "Database schema created/verified" }
        }
    }

    fun close() {
        TransactionManager.closeAndUnregister(db)
        logger.info { "Database closed" }
    }
}
