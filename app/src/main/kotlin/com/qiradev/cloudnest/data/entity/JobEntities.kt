package com.qiradev.cloudnest.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object JobKind {
    const val UPLOAD = "upload"
    const val DOWNLOAD = "download"
    const val MIRROR = "mirror"
    const val PURGE = "purge"
}

object JobStatus {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val PAUSED = "paused"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
}

object UploadAttemptResult {
    const val OK = "ok"
    const val ERROR = "error"
}

/** Space reservation held while an upload runs (specs/01 §1.8, specs/02 §4). */
@Entity(
    tableName = "reservations",
    foreignKeys = [
        ForeignKey(
            entity = ProviderAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TransferJobEntity::class,
            parentColumns = ["id"],
            childColumns = ["job_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("account_id"), Index("job_id")],
)
data class ReservationEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "bytes") val bytes: Long,
    @ColumnInfo(name = "job_id") val jobId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Upload/download queue (specs/01 §1.9): max 1 running upload + 1 running download, FIFO. */
@Entity(
    tableName = "transfer_jobs",
    foreignKeys = [
        ForeignKey(
            entity = FileEntity::class,
            parentColumns = ["id"],
            childColumns = ["file_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("file_id")],
)
data class TransferJobEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "file_id") val fileId: String?,
    @ColumnInfo(name = "status") val status: String,
    /** Safe error code only — no provider internals (specs/02 §8). */
    @ColumnInfo(name = "error_code") val errorCode: String?,
    @ColumnInfo(name = "attempts") val attempts: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Per-chunk attempt ledger for diagnostics (specs/01 §1.10); rows older than 30 days are purged. */
@Entity(
    tableName = "upload_attempts",
    foreignKeys = [
        ForeignKey(
            entity = ChunkPlacementEntity::class,
            parentColumns = ["id"],
            childColumns = ["placement_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("placement_id"), Index("finished_at")],
)
data class UploadAttemptEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "placement_id") val placementId: String,
    @ColumnInfo(name = "attempt_no") val attemptNo: Int,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    /** NULL while the attempt is still running. */
    @ColumnInfo(name = "finished_at") val finishedAt: Long?,
    @ColumnInfo(name = "result") val result: String?,
    @ColumnInfo(name = "error_code") val errorCode: String?,
)

/** Purge ledger (specs/01 §1.11) — lets retries avoid deleting a cloud object twice. */
@Entity(
    tableName = "deletion_attempts",
    foreignKeys = [
        ForeignKey(
            entity = FileEntity::class,
            parentColumns = ["id"],
            childColumns = ["file_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("file_id")],
)
data class DeletionAttemptEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "file_id") val fileId: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long?,
    @ColumnInfo(name = "result") val result: String?,
    @ColumnInfo(name = "error_code") val errorCode: String?,
    @ColumnInfo(name = "remote_ids_json") val remoteIdsJson: String?,
)
