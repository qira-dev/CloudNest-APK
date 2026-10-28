package com.qiradev.cloudnest.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.qiradev.cloudnest.data.entity.ProviderAccountEntity

@Dao
interface ProviderAccountDao {
    @Insert
    suspend fun insert(account: ProviderAccountEntity)

    @Update
    suspend fun update(account: ProviderAccountEntity)

    @Query("SELECT * FROM provider_accounts WHERE id = :id")
    suspend fun getById(id: String): ProviderAccountEntity?

    @Query("SELECT * FROM provider_accounts ORDER BY created_at")
    suspend fun getAll(): List<ProviderAccountEntity>

    @Query(
        "UPDATE provider_accounts SET quota_total_bytes = :totalBytes, quota_used_bytes = :usedBytes, " +
            "quota_status = :quotaStatus, quota_observed_at = :observedAt WHERE id = :id",
    )
    suspend fun updateQuota(id: String, totalBytes: Long?, usedBytes: Long?, quotaStatus: String, observedAt: Long)

    @Query("DELETE FROM provider_accounts WHERE id = :id")
    suspend fun delete(id: String)
}
