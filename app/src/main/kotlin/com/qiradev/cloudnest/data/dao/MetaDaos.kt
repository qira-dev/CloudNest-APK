package com.qiradev.cloudnest.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.qiradev.cloudnest.data.entity.MetaEntity
import com.qiradev.cloudnest.data.entity.SettingsEntity

@Dao
interface MetaDao {
    @Upsert
    suspend fun upsert(entry: MetaEntity)

    @Query("SELECT `value` FROM meta WHERE `key` = :key")
    suspend fun getValue(key: String): String?

    @Query("DELETE FROM meta WHERE `key` = :key")
    suspend fun delete(key: String)
}

@Dao
interface SettingsDao {
    @Upsert
    suspend fun upsert(entry: SettingsEntity)

    @Query("SELECT `value` FROM settings WHERE `key` = :key")
    suspend fun getValue(key: String): String?

    @Query("SELECT * FROM settings")
    suspend fun getAll(): List<SettingsEntity>

    @Query("DELETE FROM settings WHERE `key` = :key")
    suspend fun delete(key: String)
}
