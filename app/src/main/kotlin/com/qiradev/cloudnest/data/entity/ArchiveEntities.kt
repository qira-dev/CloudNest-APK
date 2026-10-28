package com.qiradev.cloudnest.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object FileStatus {
    const val PLANNING = "planning"
    const val UPLOADING = "uploading"
    const val COMPLETE = "complete"
    const val FAILED = "failed"
}

object PlacementRole {
    const val PRIMARY = "primary"
    const val MIRROR = "mirror"
}

object PlacementStatus {
    const val PLANNED = "planned"
    const val UPLOADING = "uploading"
    const val UPLOADED = "uploaded"
    const val VERIFIED = "verified"
    const val FAILED = "failed"
}

/** Folder tree (specs/01 §1.4). Root folder: parent_id = NULL, name = "". */
@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("parent_id")],
)
data class FolderEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "parent_id") val parentId: String?,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    @ColumnInfo(name = "purge_at") val purgeAt: Long?,
)

/** Archived file — metadata only, file bytes never enter the database (specs/01 §0). */
@Entity(
    tableName = "files",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("folder_id")],
)
data class FileEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "folder_id") val folderId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "chunk_size_mib") val chunkSizeMib: Int,
    @ColumnInfo(name = "chunk_count") val chunkCount: Int,
    @ColumnInfo(name = "sha256") val sha256: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    @ColumnInfo(name = "purge_at") val purgeAt: Long?,
)

@Entity(
    tableName = "file_chunks",
    foreignKeys = [
        ForeignKey(
            entity = FileEntity::class,
            parentColumns = ["id"],
            childColumns = ["file_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["file_id", "chunk_index"], unique = true)],
)
data class FileChunkEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "file_id") val fileId: String,
    @ColumnInfo(name = "chunk_index") val chunkIndex: Int,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "sha256") val sha256: String,
)

/** Which account holds which chunk, primary or mirror copy (specs/01 §1.7). */
@Entity(
    tableName = "chunk_placements",
    foreignKeys = [
        ForeignKey(
            entity = FileChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["chunk_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProviderAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("chunk_id"), Index(value = ["account_id", "status"])],
)
data class ChunkPlacementEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "chunk_id") val chunkId: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "role") val role: String,
    /** Cloud object id created by the upload; filled on success. */
    @ColumnInfo(name = "remote_id") val remoteId: String?,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "uploaded_at") val uploadedAt: Long?,
)
