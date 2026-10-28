package com.qiradev.cloudnest.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.qiradev.cloudnest.data.entity.DeletionAttemptEntity
import com.qiradev.cloudnest.data.entity.ReservationEntity
import com.qiradev.cloudnest.data.entity.TransferJobEntity
import com.qiradev.cloudnest.data.entity.UploadAttemptEntity

@Dao
interface ReservationDao {
    @Insert
    suspend fun insert(reservation: ReservationEntity)

    @Query("DELETE FROM reservations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM reservations WHERE job_id = :jobId")
    suspend fun deleteByJob(jobId: String): Int

    /** Capacity math input (specs/02 §4): per-account reserved byte total. */
    @Query("SELECT COALESCE(SUM(bytes), 0) FROM reservations WHERE account_id = :accountId")
    suspend fun sumBytesByAccount(accountId: String): Long

    /**
     * Startup recovery (specs/01 §1.8): drop reservations whose job no longer exists.
     * Only bites when the FK cascade could not run (e.g. restored backup with FK off).
     */
    @Query("DELETE FROM reservations WHERE job_id NOT IN (SELECT id FROM transfer_jobs)")
    suspend fun deleteOrphaned(): Int

    /** Startup recovery (specs/02 §7): reservations of jobs no longer alive. */
    @Query("DELETE FROM reservations WHERE job_id IN (SELECT id FROM transfer_jobs WHERE status NOT IN ('queued', 'running', 'paused'))")
    suspend fun deleteForInactiveJobs(): Int

    @Query("SELECT * FROM reservations")
    suspend fun getAll(): List<ReservationEntity>
}

@Dao
interface TransferJobDao {
    @Insert
    suspend fun insert(job: TransferJobEntity)

    @Update
    suspend fun update(job: TransferJobEntity)

    @Query("SELECT * FROM transfer_jobs WHERE id = :id")
    suspend fun getById(id: String): TransferJobEntity?

    /** Queue head per kind — FIFO by creation; one upload + one download run at a time. */
    @Query("SELECT * FROM transfer_jobs WHERE kind = :kind AND status = 'queued' ORDER BY created_at LIMIT 1")
    suspend fun nextQueued(kind: String): TransferJobEntity?

    @Query("SELECT * FROM transfer_jobs WHERE status = :status ORDER BY created_at")
    suspend fun listByStatus(status: String): List<TransferJobEntity>

    @Query("SELECT * FROM transfer_jobs WHERE kind = :kind AND status = :status ORDER BY created_at")
    suspend fun listByKindAndStatus(kind: String, status: String): List<TransferJobEntity>

    @Query("DELETE FROM transfer_jobs WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface UploadAttemptDao {
    @Insert
    suspend fun insert(attempt: UploadAttemptEntity)

    @Update
    suspend fun update(attempt: UploadAttemptEntity)

    @Query("SELECT * FROM upload_attempts WHERE placement_id = :placementId ORDER BY attempt_no")
    suspend fun listByPlacement(placementId: String): List<UploadAttemptEntity>

    /** 30-day retention (specs/01 §1.10). */
    @Query("DELETE FROM upload_attempts WHERE finished_at IS NOT NULL AND finished_at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    /** Health replay (specs/06 §2.1): recent outcomes with the owning account. */
    @Query(
        "SELECT chunk_placements.account_id AS accountId, upload_attempts.result AS result, " +
            "upload_attempts.error_code AS errorCode, upload_attempts.finished_at AS finishedAt " +
            "FROM upload_attempts JOIN chunk_placements ON chunk_placements.id = upload_attempts.placement_id " +
            "WHERE upload_attempts.finished_at IS NOT NULL AND upload_attempts.finished_at >= :since",
    )
    suspend fun recentAttemptOutcomes(since: Long): List<AttemptOutcome>
}

/** Flat projection for [UploadAttemptDao.recentAttemptOutcomes]. */
data class AttemptOutcome(
    val accountId: String,
    val result: String?,
    val errorCode: String?,
    val finishedAt: Long,
)

@Dao
interface DeletionAttemptDao {
    @Insert
    suspend fun insert(attempt: DeletionAttemptEntity)

    @Update
    suspend fun update(attempt: DeletionAttemptEntity)

    @Query("SELECT * FROM deletion_attempts WHERE id = :id")
    suspend fun getById(id: String): DeletionAttemptEntity?

    @Query("SELECT * FROM deletion_attempts WHERE file_id = :fileId ORDER BY started_at")
    suspend fun listByFile(fileId: String): List<DeletionAttemptEntity>

    @Query("DELETE FROM deletion_attempts WHERE file_id = :fileId")
    suspend fun deleteByFile(fileId: String): Int
}
