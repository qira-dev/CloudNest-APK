package com.qiradev.cloudnest.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qiradev.cloudnest.data.entity.AuditEventEntity
import com.qiradev.cloudnest.data.entity.AuditEventType
import com.qiradev.cloudnest.data.entity.ChunkPlacementEntity
import com.qiradev.cloudnest.data.entity.DeletionAttemptEntity
import com.qiradev.cloudnest.data.entity.FileChunkEntity
import com.qiradev.cloudnest.data.entity.FileEntity
import com.qiradev.cloudnest.data.entity.FileStatus
import com.qiradev.cloudnest.data.entity.FolderEntity
import com.qiradev.cloudnest.data.entity.JobKind
import com.qiradev.cloudnest.data.entity.JobStatus
import com.qiradev.cloudnest.data.entity.NotificationEntity
import com.qiradev.cloudnest.data.entity.NotificationType
import com.qiradev.cloudnest.data.entity.PlacementRole
import com.qiradev.cloudnest.data.entity.PlacementStatus
import com.qiradev.cloudnest.data.entity.ProviderAccountEntity
import com.qiradev.cloudnest.data.entity.ProviderAccountStatus
import com.qiradev.cloudnest.data.entity.Providers
import com.qiradev.cloudnest.data.entity.QuotaStatus
import com.qiradev.cloudnest.data.entity.ReservationEntity
import com.qiradev.cloudnest.data.entity.TransferJobEntity
import com.qiradev.cloudnest.data.entity.UploadAttemptEntity
import com.qiradev.cloudnest.data.entity.UploadAttemptResult

/** Synthetic fixtures only — no real data reaches the repository (AGENT.md rule 9). */
fun buildTestDb(): CloudNestDatabase = Room.inMemoryDatabaseBuilder(
    ApplicationProvider.getApplicationContext(),
    CloudNestDatabase::class.java,
)
    .addCallback(CloudNestDatabase.CALLBACK)
    .allowMainThreadQueries()
    .build()

/** Same schema but SQLite default foreign_keys=OFF — for startup-recovery style checks. */
fun buildTestDbWithoutForeignKeys(): CloudNestDatabase = Room.inMemoryDatabaseBuilder(
    ApplicationProvider.getApplicationContext(),
    CloudNestDatabase::class.java,
)
    .allowMainThreadQueries()
    .build()

fun testAccount(
    id: String = "acc-1",
    provider: String = Providers.GOOGLE_DRIVE,
    status: String = ProviderAccountStatus.CONNECTED,
    quotaStatus: String = QuotaStatus.CURRENT,
) = ProviderAccountEntity(
    id = id,
    provider = provider,
    label = "Test drive",
    status = status,
    encryptedTokens = null,
    tokenExpiresAt = 1_000L,
    quotaTotalBytes = 100L,
    quotaUsedBytes = 10L,
    quotaUnlimited = 0,
    quotaStatus = quotaStatus,
    quotaObservedAt = 2_000L,
    quotaScopeKey = null,
    rootPath = "root-folder-id",
    createdAt = 3_000L,
    lastUsedAt = 4_000L,
)

fun testFolder(id: String = "folder-1", parent: String? = null, name: String = "docs") = FolderEntity(
    id = id,
    parentId = parent,
    name = name,
    createdAt = 1_000L,
    deletedAt = null,
    purgeAt = null,
)

fun testFile(id: String = "file-1", folderId: String = "folder-1", name: String = "a.mp4") = FileEntity(
    id = id,
    folderId = folderId,
    name = name,
    sizeBytes = 1_000_000L,
    mimeType = "video/mp4",
    chunkSizeMib = 64,
    chunkCount = 1,
    sha256 = "file-hash-$id",
    status = FileStatus.UPLOADING,
    createdAt = 1_000L,
    updatedAt = 1_000L,
    deletedAt = null,
    purgeAt = null,
)

fun testChunk(id: String = "chunk-1", fileId: String = "file-1", index: Int = 0) = FileChunkEntity(
    id = id,
    fileId = fileId,
    chunkIndex = index,
    sizeBytes = 1_000_000L,
    sha256 = "chunk-hash-$index",
)

fun testPlacement(
    id: String = "pl-1",
    chunkId: String = "chunk-1",
    accountId: String = "acc-1",
) = ChunkPlacementEntity(
    id = id,
    chunkId = chunkId,
    accountId = accountId,
    role = PlacementRole.PRIMARY,
    remoteId = null,
    status = PlacementStatus.PLANNED,
    uploadedAt = null,
)

fun testJob(
    id: String = "job-1",
    kind: String = JobKind.UPLOAD,
    fileId: String? = "file-1",
    createdAt: Long = 1_000L,
) = TransferJobEntity(
    id = id,
    kind = kind,
    fileId = fileId,
    status = JobStatus.QUEUED,
    errorCode = null,
    attempts = 0,
    createdAt = createdAt,
    updatedAt = createdAt,
)

fun testReservation(
    id: String = "res-1",
    accountId: String = "acc-1",
    jobId: String = "job-1",
    bytes: Long = 500_000L,
) = ReservationEntity(
    id = id,
    accountId = accountId,
    bytes = bytes,
    jobId = jobId,
    createdAt = 1_000L,
)

fun testUploadAttempt(
    id: String = "att-1",
    placementId: String = "pl-1",
    attemptNo: Int = 1,
    finishedAt: Long? = 2_000L,
    result: String? = UploadAttemptResult.OK,
) = UploadAttemptEntity(
    id = id,
    placementId = placementId,
    attemptNo = attemptNo,
    startedAt = 1_000L,
    finishedAt = finishedAt,
    result = result,
    errorCode = null,
)

fun testDeletionAttempt(id: String = "del-1", fileId: String = "file-1") = DeletionAttemptEntity(
    id = id,
    fileId = fileId,
    startedAt = 1_000L,
    finishedAt = 2_000L,
    result = UploadAttemptResult.OK,
    errorCode = null,
    remoteIdsJson = """["remote-1"]""",
)

fun testNotification(id: String = "ntf-1", createdAt: Long = 1_000L, read: Int = 0) = NotificationEntity(
    id = id,
    type = NotificationType.UPLOAD_DONE,
    payload = """{"file":"a.mp4"}""",
    read = read,
    createdAt = createdAt,
)

fun testAuditEvent(id: String = "aud-1", createdAt: Long = 1_000L) = AuditEventEntity(
    id = id,
    type = AuditEventType.ACCOUNT_CONNECTED,
    detailJson = """{"provider":"google_drive"}""",
    createdAt = createdAt,
)
