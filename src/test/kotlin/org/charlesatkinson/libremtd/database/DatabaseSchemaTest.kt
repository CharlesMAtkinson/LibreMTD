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

package org.charlesatkinson.libremtd.database

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Runs the real Database.init against a new temporary file and reads the
 * table names straight from SQLite with plain JDBC, so it checks what the
 * application really creates. In particular it guards against the removed
 * allowances feature coming back by accident.
 */
class DatabaseSchemaTest {

    @Test
    fun `init creates the schema but no allowance table`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("schema-test.db")

        Database.init(file)
        try {
            val tables = DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1)) }
                    }
                }
            }

            assertTrue("properties" in tables, tables.toString())
            assertTrue("submissions" in tables, tables.toString())
            assertTrue(tables.none { it.contains("allowance", ignoreCase = true) }, tables.toString())
        } finally {
            Database.close()
        }
    }
}
