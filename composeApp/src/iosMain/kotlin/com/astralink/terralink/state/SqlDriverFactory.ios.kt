package com.astralink.terralink.state

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.astralink.terralink.db.TerralinkDb

actual fun createSqlDriver(): SqlDriver =
    NativeSqliteDriver(TerralinkDb.Schema, "terralink.db")
