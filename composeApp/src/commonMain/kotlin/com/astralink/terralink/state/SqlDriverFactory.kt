package com.astralink.terralink.state

import app.cash.sqldelight.db.SqlDriver
import com.astralink.terralink.db.TerralinkDb

/**
 * Platform-specific SqlDriver factory for the local readings cache.
 * Each actual picks an appropriate native driver and on-disk location;
 * the schema is `TerralinkDb.Schema` generated from .sq files.
 */
expect fun createSqlDriver(): SqlDriver

fun createTerralinkDb(): TerralinkDb = TerralinkDb(createSqlDriver())
