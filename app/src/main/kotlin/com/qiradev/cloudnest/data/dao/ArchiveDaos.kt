package com.qiradev.cloudnest.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.qiradev.cloudnest.data.entity.ChunkPlacementEntity
import com.qiradev.cloudnest.data.entity.FileChunkEntity
import com.qiradev.cloudnest.data.entity.FileEntity
import com.qiradev.cloudnest.data.entity.FolderEntity

@Dao
interface FolderDao {
    @Insert
    suspend fun insert(folder: FolderEntity)

    @Update
    suspend fun update(folder: FolderEntity)

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getById(id: String): FolderEntity?

    /** parentId = NULL returns the root children (IS matches the bound NULL). */
    @Query("SELECT * FROM folders WHERE parent_id IS :parentId AND deleted_at IS NULL ORDER BY name COLLATE NOCASE")
    suspend fun listChildren(parentId: String?): List<FolderEntity>

    /** The app root (specs/01 §1.4): parent_id = NULL and name = "". */
    @Query("SELECT * FROM folders WHERE parent_id IS NULL AND name = '' LIMIT 1")
    suspend fun getRoot(): FolderEntity?

    /** Whole tree in one read — descendant walks are computed in Kotlin. */
    @Query("SELECT * FROM folders ORDER BY created_at")
    suspend fun listAll(): List<FolderEntity>

    /** Trash screen + purge scan (specs/01 §1.4). */
    @Query("SELECT * FROM folders WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    suspend fun listTrashed(): List<FolderEntity>

    /** Rows whose retention window (purge_at) has elapsed (specs/01 §1.4). */
    @Query("SELECT * FROM folders WHERE deleted_at IS NOT NULL AND purge_at IS NOT NULL AND purge_at <= :now")
    suspend fun listPurgeDue(now: Long): List<FolderEntity>

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface FileDao {
    @Insert
    suspend fun insert(file: FileEntity)

    @Update
    suspend fun update(file: FileEntity)

    @Query("SELECT * FROM files WHERE id = :id")
    suspend fun getById(id: String): FileEntity?

    @Query("SELECT * FROM files WHERE folder_id = :folderId AND deleted_at IS NULL ORDER BY name COLLATE NOCASE")
    suspend fun listByFolder(folderId: String): List<FileEntity>

    /** All file rows under the given folders, trashed or not (trash cascade + restore). */
    @Query("SELECT * FROM files WHERE folder_id IN (:folderIds)")
    suspend fun listByFolders(folderIds: List<String>): List<FileEntity>

    /** Trash screen + purge scan (specs/01 §1.5). */
    @Query("SELECT * FROM files WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    suspend fun listTrashed(): List<FileEntity>

    /** Rows whose retention window (purge_at) has elapsed (specs/01 §1.5). */
    @Query("SELECT * FROM files WHERE deleted_at IS NOT NULL AND purge_at IS NOT NULL AND purge_at <= :now")
    suspend fun listPurgeDue(now: Long): List<FileEntity>

    /** Queue screen labels (specs/09 screen 7): files referenced by transfer jobs. */
    @Query("SELECT * FROM files WHERE id IN (:ids)")
    suspend fun getAllByIds(ids: List<String>): List<FileEntity>

    /** Duplicate-name guard for uploads (`name (2)` renaming happens in the engine); trashed names are freed. */
    @Query("SELECT COUNT(*) FROM files WHERE folder_id = :folderId AND name = :name AND deleted_at IS NULL")
    suspend fun countByNameInFolder(folderId: String, name: String): Int

    @Query("DELETE FROM files WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface FileChunkDao {
    @Insert
    suspend fun insert(chunk: FileChunkEntity)

    @Query("SELECT * FROM file_chunks WHERE id = :id")
    suspend fun getById(id: String): FileChunkEntity?

    @Query("SELECT * FROM file_chunks WHERE file_id = :fileId ORDER BY chunk_index")
    suspend fun listByFile(fileId: String): List<FileChunkEntity>

    @Query("SELECT COUNT(*) FROM file_chunks WHERE file_id = :fileId")
    suspend fun countByFile(fileId: String): Int

    @Query("DELETE FROM file_chunks WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ChunkPlacementDao {
    @Insert
    suspend fun insert(placement: ChunkPlacementEntity)

    @Update
    suspend fun update(placement: ChunkPlacementEntity)

    @Query("SELECT * FROM chunk_placements WHERE id = :id")
    suspend fun getById(id: String): ChunkPlacementEntity?

    @Query("SELECT * FROM chunk_placements WHERE chunk_id = :chunkId")
    suspend fun listByChunk(chunkId: String): List<ChunkPlacementEntity>

    /** All placements of a file's chunks (upload/download engine working set). */
    @Query(
        "SELECT chunk_placements.* FROM chunk_placements " +
            "JOIN file_chunks ON file_chunks.id = chunk_placements.chunk_id " +
            "WHERE file_chunks.file_id = :fileId ORDER BY file_chunks.chunk_index",
    )
    suspend fun listByFile(fileId: String): List<ChunkPlacementEntity>

    @Query("SELECT * FROM chunk_placements WHERE account_id = :accountId AND status = :status")
    suspend fun listByAccountAndStatus(accountId: String, status: String): List<ChunkPlacementEntity>

    @Query("DELETE FROM chunk_placements WHERE id = :id")
    suspend fun delete(id: String)
}
