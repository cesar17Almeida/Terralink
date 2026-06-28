package com.astralink.terralink.state

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.astralink.terralink.db.TerralinkDb
import java.io.File
import java.util.Properties

actual fun createSqlDriver(): SqlDriver {
    val home = System.getProperty("user.home") ?: "."
    val dir = File(home, ".terralink").apply { mkdirs() }
    val dbFile = File(dir, "terralink.db")
    val isNew = !dbFile.exists()
    val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", Properties())
    val schema = TerralinkDb.Schema

    if (isNew) {
        schema.create(driver)
    } else {
        // The JdbcSqliteDriver doesn't track schema versions for us (unlike the
        // native/Android drivers), so do it by hand: migrate an older on-disk schema
        // up to the current one (e.g. add the LoRa console table). Migrations use
        // CREATE IF NOT EXISTS, so a DB whose user_version was never stamped (0) is
        // brought up safely too.
        val current = driver.executeQuery(
            identifier = null,
            sql = "PRAGMA user_version",
            mapper = { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) ?: 0L) },
            parameters = 0,
        ).value
        if (current < schema.version) schema.migrate(driver, current, schema.version)
    }
    driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
    return driver
}
