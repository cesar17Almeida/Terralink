package com.astralink.terralink.state

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.astralink.terralink.ble.AndroidBleContext
import com.astralink.terralink.db.TerralinkDb

actual fun createSqlDriver(): SqlDriver =
    AndroidSqliteDriver(
        schema = TerralinkDb.Schema,
        context = AndroidBleContext.appContext,
        name = "terralink.db",
    )
