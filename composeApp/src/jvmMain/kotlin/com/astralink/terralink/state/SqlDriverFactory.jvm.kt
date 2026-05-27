package com.astralink.terralink.state

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
    if (isNew) TerralinkDb.Schema.create(driver)
    return driver
}
