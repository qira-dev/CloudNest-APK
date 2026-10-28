package com.qiradev.cloudnest.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Schema version tracking (specs/01 §1.1). Room keeps its own version in room_master_table. */
@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String,
)

/** Key-value app settings (specs/01 §1.2). Keys and defaults live in data.SettingsKeys. */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String,
)
