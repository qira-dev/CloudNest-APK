package com.qiradev.cloudnest.engine.upload

import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.entity.ChunkPlacementEntity
import com.qiradev.cloudnest.data.entity.FileChunkEntity
import com.qiradev.cloudnest.data.entity.FileEntity
import com.qiradev.cloudnest.data.entity.FileStatus
import com.qiradev.cloudnest.data.entity.JobKind
import com.qiradev.cloudnest.data.entity.JobStatus
import com.qiradev.cloudnest.data.entity.NotificationEntity
import com.qiradev.cloudnest.data.entity.NotificationType
import com.qiradev.cloudnest.data.entity.PlacementRole
import com.qiradev.cloudnest.data.entity.PlacementStatus
import com.qiradev.cloudnest.data.entity.ProviderAccountStatus
import com.qiradev.cloudnest.data.entity.ReservationEntity
import com.qiradev.cloudnest.data.entity.TransferJobEntity
import com.qiradev.cloudnest.data.entity.UploadAttemptEntity
import com.qiradev.cloudnest.data.entity.UploadAttemptResult
import com.qiradev.cloudnest.engine.health.Health
import com.qiradev.cloudnest.engine.health.HealthTracker
import com.qiradev.cloudnest.engine.providers.ProviderConnector
import com.qiradev.cloudnest.engine.providers.ProviderErrorCode
import com.qiradev.cloudnest.engine.providers.ProviderException
import com.qiradev.cloudnest.engine.providers.TokenPair
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay

/** Fixed safe error codes shown in UI/diagnostics (specs/02 §8). */
object UploadErrorCode {
    const val NO_FREE_SPACE = "no_free_space"
    const val QUOTA_STALE = "quota_stale"
    const val QUOTA_UNKNOWN = "quota_unknown"
    const val NETWORK_UNREACHABLE = "network_unreachable"
    const val WIFI_REQUIRED = "wifi_required"
    const val PROVIDER_DOWN = "provider_down"
    const val AUTH_EXPIRED = "auth_expired"
    const val CHUNK_HASH_MISMATCH = "chunk_hash_mismatch"
    const val FILE_CHANGED_DURING_UPLOAD = "file_changed_during_upload"
    const val PROVIDER_RATE_LIMITED = "provider_rate_limited"
    const val UNKNOWN = "unknown"
}

/** Wi-Fi-only policy (specs/02 §5): mobile data must not start uploads. */
interface NetworkGate {
    /** True when the current network satisfies the policy. */
    fun isSatisfied(): Boolean

    object AlwaysAllowed : NetworkGate {
        override fun isSatisfied() = true
    }
}

/** Production: `AccountConnectionManager::getValidTokens`. Tests: canned pairs. */
fun interface TokenProvider {
    suspend fun validTokens(accountId: String): TokenPair
}

data class UploadSettings(
    val chunkSizeMib: Int = ChunkMath.DEFAULT_CHUNK_SIZE_MIB,
    val reservePerAccountBytes: Long = 0,
    val safetyMarginBytes: Long = 0,
    val strategy: String = RoutingStrategy.CAPACITY_AWARE,
    /** specs/06 §1.1 — mirroring defaults to on; only the settings store turns it off. */
    val mirrorEnabled: Boolean = true,
)

/** Terminal job failure carrying the safe error code. */
class UploadJobException(val errorCode: String, cause: Throwable? = null) : Exception(errorCode, cause)

/** Raised when the Wi-Fi policy pauses a running job mid-flight. */
class WifiPauseRequiredException(val errorCode: String = UploadErrorCode.WIFI_REQUIRED) :
    Exception(errorCode)

/**
 * Upload engine (specs/02): planning → allocating → uploading → verifying → done,
 * fully persisted in the Room data layer so pause/resume/crash recovery never
 * loses work. File bytes stream through in chunks — never whole in memory.
 */
class UploadService(
    private val db: CloudNestDatabase,
    private val connectors: Map<String, ProviderConnector>,
    private val tokens: TokenProvider,
    private val networkGate: NetworkGate = NetworkGate.AlwaysAllowed,
    private val settings: UploadSettings = UploadSettings(),
    /** Health ledger (specs/06 §2) — Down accounts leave the upload routing pool. */
    private val health: HealthTracker? = null,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // Transient registry: the caller (UI/worker) re-registers sources after a
    // restart from the SAF uri it stored; the DB keeps everything else.
    private val sources = ConcurrentHashMap<String, UploadFileSource>()

    fun registerSource(fileId: String, source: UploadFileSource) {
        sources[fileId] = source
    }

    // ---- queueing ----

    /** Creates the file row (planning) + a queued upload job. Duplicate names become `name (2)`. */
    suspend fun enqueue(source: UploadFileSource, folderId: String, name: String, mimeType: String): String {
        val now = clock()
        val fileId = UUID.randomUUID().toString()
        db.fileDao().insert(
            FileEntity(
                id = fileId,
                folderId = folderId,
                name = dedupName(folderId, name),
                sizeBytes = source.sizeBytes(),
                mimeType = mimeType,
                chunkSizeMib = settings.chunkSizeMib,
                chunkCount = 0,
                sha256 = "",
                status = FileStatus.PLANNING,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
                purgeAt = null,
            ),
        )
        sources[fileId] = source
        val jobId = UUID.randomUUID().toString()
        db.transferJobDao().insert(
            TransferJobEntity(
                id = jobId,
                kind = JobKind.UPLOAD,
                fileId = fileId,
                status = JobStatus.QUEUED,
                errorCode = null,
                attempts = 0,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return jobId
    }

    /** Runs one queued job to completion (or pause/failure). One upload runs at a time. */
    suspend fun runJob(jobId: String) {
        val initial = db.transferJobDao().getById(jobId) ?: return
        if (initial.status != JobStatus.QUEUED) return
        val job = initial.copy(status = JobStatus.RUNNING, updatedAt = clock())
        db.transferJobDao().update(job)

        val file = job.fileId?.let { db.fileDao().getById(it) }
        if (file == null) {
            failJob(jobId, UploadErrorCode.UNKNOWN)
            return
        }

        // specs/02 §5: mobile data never starts an upload under Wi-Fi-only.
        if (!networkGate.isSatisfied()) {
            db.transferJobDao().update(job.copy(status = JobStatus.QUEUED, updatedAt = clock()))
            return
        }

        try {
            val source = requireNotNull(sources[file.id]) {
                "no source registered for ${file.id} (re-register after restart)"
            }

            var chunks = db.fileChunkDao().listByFile(file.id)
            if (file.sha256.isEmpty()) {
                chunks = planFile(file, source)
            }

            var placements = db.chunkPlacementDao().listByFile(file.id)
            if (placements.isEmpty()) {
                placements = allocate(file, chunks, jobId, source)
            }
            if (db.transferJobDao().getById(jobId)?.status != JobStatus.RUNNING) return

            db.fileDao().update(
                db.fileDao().getById(file.id)!!.copy(status = FileStatus.UPLOADING, updatedAt = clock()),
            )
            uploadAndVerify(file, chunks, jobId, source)

            val finished = db.transferJobDao().getById(jobId)!!
            if (finished.status == JobStatus.RUNNING) {
                db.fileDao().update(
                    db.fileDao().getById(file.id)!!.copy(status = FileStatus.COMPLETE, updatedAt = clock()),
                )
                db.transferJobDao().update(finished.copy(status = JobStatus.DONE, errorCode = null, updatedAt = clock()))
                // Job finished → its space reservations are released (specs/01 §1.8).
                db.reservationDao().deleteByJob(jobId)
                notify(NotificationType.UPLOAD_DONE, """{"file":"${jsonEscape(db.fileDao().getById(file.id)!!.name)}"}""")
                // specs/06 §1.2: mirroring is its own job AFTER the upload is complete.
                enqueueMirrorJobIfEligible(file.id)
            }
        } catch (e: UploadJobException) {
            failJob(jobId, e.errorCode)
        }
    }

    // ---- user controls (specs/02 §5) ----

    suspend fun pause(jobId: String) {
        val job = db.transferJobDao().getById(jobId) ?: return
        if (job.status in listOf(JobStatus.QUEUED, JobStatus.RUNNING)) {
            db.transferJobDao().update(job.copy(status = JobStatus.PAUSED, updatedAt = clock()))
        }
    }

    /** Resume re-runs from where the job stood; verified chunks are never re-sent. */
    suspend fun resume(jobId: String) {
        val job = db.transferJobDao().getById(jobId) ?: return
        if (job.status == JobStatus.PAUSED) {
            db.transferJobDao().update(job.copy(status = JobStatus.QUEUED, errorCode = null, updatedAt = clock()))
        }
    }

    suspend fun cancel(jobId: String) {
        val job = db.transferJobDao().getById(jobId) ?: return
        if (job.status in listOf(JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.PAUSED)) {
            db.transferJobDao().update(job.copy(status = JobStatus.CANCELLED, updatedAt = clock()))
            db.reservationDao().deleteByJob(jobId)
            job.fileId?.let { fileId ->
                db.fileDao().getById(fileId)?.let { file ->
                    if (file.status != FileStatus.COMPLETE) {
                        db.fileDao().update(file.copy(status = FileStatus.FAILED, updatedAt = clock()))
                    }
                }
            }
        }
    }

    /** User retry: failed job goes back to queued; failed chunks are re-planned as planned. */
    suspend fun retry(jobId: String) {
        val job = db.transferJobDao().getById(jobId) ?: return
        if (job.status != JobStatus.FAILED) return
        db.transferJobDao().update(job.copy(status = JobStatus.QUEUED, errorCode = null, updatedAt = clock()))
        job.fileId?.let { fileId ->
            db.chunkPlacementDao().listByFile(fileId).forEach { placement ->
                if (placement.status == PlacementStatus.FAILED) {
                    db.chunkPlacementDao().update(placement.copy(status = PlacementStatus.PLANNED, remoteId = null))
                }
            }
            db.fileDao().getById(fileId)?.let { file ->
                if (file.status == FileStatus.FAILED) {
                    db.fileDao().update(file.copy(status = FileStatus.PLANNING, updatedAt = clock()))
                }
            }
        }
    }

    // ---- startup recovery (specs/02 §7) ----

    suspend fun recoverAtStartup() {
        db.reservationDao().deleteForInactiveJobs()
        db.reservationDao().deleteOrphaned()
        db.transferJobDao().listByKindAndStatus(JobKind.UPLOAD, JobStatus.RUNNING).forEach { job ->
            db.transferJobDao().update(job.copy(status = JobStatus.QUEUED, updatedAt = clock()))
        }
    }

    // ---- phases ----

    /**
     * Planning: one streaming pass computes the whole-file hash AND every chunk
     * hash; `file_chunks` rows are created (specs/02 §1+§9).
     */
    private suspend fun planFile(file: FileEntity, source: UploadFileSource): List<FileChunkEntity> {
        val size = source.sizeBytes()
        val chunkSizeMib = file.chunkSizeMib
        val count = ChunkMath.chunkCount(size, chunkSizeMib)
        val fileDigest = java.security.MessageDigest.getInstance("SHA-256")
        val rows = ArrayList<FileChunkEntity>(count)
        source.openStream().use { stream ->
            val buffer = ByteArray(READ_BUFFER)
            for (index in 0 until count) {
                val chunkSize = ChunkMath.chunkSizeAt(size, chunkSizeMib, index)
                val chunkDigest = java.security.MessageDigest.getInstance("SHA-256")
                var left = chunkSize
                while (left > 0) {
                    val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (read < 0) throw UploadJobException(UploadErrorCode.FILE_CHANGED_DURING_UPLOAD)
                    chunkDigest.update(buffer, 0, read)
                    fileDigest.update(buffer, 0, read)
                    left -= read
                }
                rows += FileChunkEntity(
                    id = UUID.randomUUID().toString(),
                    fileId = file.id,
                    chunkIndex = index,
                    sizeBytes = chunkSize,
                    sha256 = Hashing.hex(chunkDigest.digest()),
                )
            }
        }
        rows.forEach { db.fileChunkDao().insert(it) }
        db.fileDao().update(
            file.copy(sizeBytes = size, sha256 = Hashing.hex(fileDigest.digest()), chunkCount = count, updatedAt = clock()),
        )
        return rows
    }

    /**
     * Allocating: capacity pre-flight (§2), routing with the chunk-size ladder
     * (§3.3), then planned placements + all-or-nothing reservations.
     */
    private suspend fun allocate(
        file: FileEntity,
        chunks: List<FileChunkEntity>,
        jobId: String,
        source: UploadFileSource,
    ): List<ChunkPlacementEntity> {
        val outstanding = HashMap<String, Long>()
        db.reservationDao().getAll().forEach { reservation ->
            outstanding.merge(reservation.accountId, reservation.bytes, Long::plus)
        }
        val capacities = Capacity.evaluate(
            db.providerAccountDao().getAll(),
            outstanding,
            settings.reservePerAccountBytes,
            settings.safetyMarginBytes,
        )
        // specs/06 §2.2: Down accounts never receive new uploads.
        val eligible = capacities.filter { capacity ->
            capacity.isEligible && health?.verdict(capacity.representativeId) != Health.DOWN
        }

        var chosenSize = file.chunkSizeMib
        var plan: RoutePlan? = null
        while (true) {
            val sizes = chunkSizesFor(source.sizeBytes(), chosenSize)
            plan = try {
                Router.route(eligible, sizes, settings.strategy)
            } catch (e: NoPlanException) {
                null
            }
            if (plan != null) break
            chosenSize = ChunkMath.suggestSmallerChunkSizeMib(chosenSize)
                ?: throw UploadJobException(UploadErrorCode.NO_FREE_SPACE)
        }
        val finalSize = chosenSize

        // A shrunk chunk size invalidates the planned chunk boundaries — re-plan hashes.
        val effectiveChunks = if (finalSize != file.chunkSizeMib) {
            db.fileDao().update(file.copy(chunkSizeMib = finalSize, updatedAt = clock()))
            chunks.forEach { db.fileChunkDao().delete(it.id) }
            planFile(db.fileDao().getById(file.id)!!, source)
        } else {
            chunks
        }

        val byIndex = effectiveChunks.associateBy { it.chunkIndex }
        val placements = plan.assignments.mapIndexed { index, accountId ->
            val chunk = byIndex.getValue(index)
            val placement = ChunkPlacementEntity(
                id = UUID.randomUUID().toString(),
                chunkId = chunk.id,
                accountId = accountId,
                role = PlacementRole.PRIMARY,
                remoteId = null,
                status = PlacementStatus.PLANNED,
                uploadedAt = null,
            )
            db.chunkPlacementDao().insert(placement)
            placement
        }
        plan.plannedBytesByAccount.forEach { (accountId, bytes) ->
            if (bytes > 0) {
                db.reservationDao().insert(
                    ReservationEntity(
                        id = UUID.randomUUID().toString(),
                        accountId = accountId,
                        bytes = bytes,
                        jobId = jobId,
                        createdAt = clock(),
                    ),
                )
            }
        }
        return placements
    }

    private suspend fun uploadAndVerify(
        file: FileEntity,
        chunks: List<FileChunkEntity>,
        jobId: String,
        source: UploadFileSource,
    ) {
        val placements = db.chunkPlacementDao().listByFile(file.id)
        val placementByChunk = placements.associateBy { it.chunkId }

        // Phase: uploading (specs/02 §4).
        for (chunk in chunks) {
            if (!jobStillRunning(jobId)) return
            val placement = placementByChunk[chunk.id] ?: continue
            if (placement.status in listOf(PlacementStatus.UPLOADED, PlacementStatus.VERIFIED)) continue
            uploadWithRetries(file, chunk, placement, source, jobId)
        }

        // Phase: verifying — re-download and hash-compare every uploaded chunk.
        for (chunk in chunks) {
            if (!jobStillRunning(jobId)) return
            val placement = placementByChunk[chunk.id] ?: continue
            val current = db.chunkPlacementDao().getById(placement.id) ?: continue
            if (current.status != PlacementStatus.UPLOADED) continue
            verifyWithRetries(file, chunk, current, source, jobId)
        }
    }

    private suspend fun uploadWithRetries(
        file: FileEntity,
        chunk: FileChunkEntity,
        placement: ChunkPlacementEntity,
        source: UploadFileSource,
        jobId: String,
    ) {
        val account = db.providerAccountDao().getById(placement.accountId)
            ?: throw UploadJobException(UploadErrorCode.UNKNOWN)
        val connector = connectors[account.provider]
            ?: throw UploadJobException(UploadErrorCode.UNKNOWN)
        val root = account.rootPath ?: run {
            val rootId = connector.ensureRoot(tokens.validTokens(account.id))
            db.providerAccountDao().update(account.copy(rootPath = rootId))
            rootId
        }
        val name = "${placement.id}.bin" // specs/04 §1: crash-recoverable chunk naming.

        while (true) {
            val attemptNo = newAttempt(placement.id)
            try {
                if (!networkGate.isSatisfied()) throw WifiPauseRequiredException()
                if (source.sizeBytes() != file.sizeBytes) {
                    throw UploadJobException(UploadErrorCode.FILE_CHANGED_DURING_UPLOAD)
                }
                val bytes = readChunk(source, file, chunk)
                val remoteId = connector.uploadChunk(tokens.validTokens(account.id), root, name, bytes)
                db.chunkPlacementDao().update(
                    placement.copy(status = PlacementStatus.UPLOADED, remoteId = remoteId, uploadedAt = clock()),
                )
                finishAttempt(placement.id, attemptNo, UploadAttemptResult.OK, null)
                health?.recordSuccess(account.id, kind = "upload")
                return
            } catch (e: WifiPauseRequiredException) {
                abandonAttempt(placement.id, attemptNo)
                pause(jobId)
                db.transferJobDao().getById(jobId)?.let {
                    db.transferJobDao().update(it.copy(errorCode = UploadErrorCode.WIFI_REQUIRED, updatedAt = clock()))
                }
                return
            } catch (e: Throwable) {
                val code = errorCodeOf(e)
                finishAttempt(placement.id, attemptNo, UploadAttemptResult.ERROR, code)
                health?.recordFailure(account.id, code, kind = "upload")
                if (!isRetryable(code) || attemptNo >= MAX_ATTEMPTS) {
                    db.chunkPlacementDao().update(placement.copy(status = PlacementStatus.FAILED))
                    throw UploadJobException(code, e)
                }
                delayFn(BACKOFF_MS[attemptNo - 1])
            }
        }
    }

    private suspend fun verifyWithRetries(
        file: FileEntity,
        chunk: FileChunkEntity,
        placement: ChunkPlacementEntity,
        source: UploadFileSource,
        jobId: String,
    ) {
        val account = db.providerAccountDao().getById(placement.accountId)
            ?: throw UploadJobException(UploadErrorCode.UNKNOWN)
        val connector = connectors[account.provider]
            ?: throw UploadJobException(UploadErrorCode.UNKNOWN)
        var current = placement

        while (true) {
            val attemptNo = newAttempt(current.id)
            try {
                if (!networkGate.isSatisfied()) throw WifiPauseRequiredException()
                val remoteId = requireNotNull(current.remoteId) { "uploaded placement without remote_id" }
                val bytes = connector.downloadChunk(tokens.validTokens(account.id), remoteId)
                if (bytes.size.toLong() != chunk.sizeBytes ||
                    !Hashing.sha256(bytes).contentEquals(chunk.sha256.hexToBytes())
                ) {
                    throw UploadJobException(UploadErrorCode.CHUNK_HASH_MISMATCH)
                }
                db.chunkPlacementDao().update(current.copy(status = PlacementStatus.VERIFIED))
                finishAttempt(current.id, attemptNo, UploadAttemptResult.OK, null)
                health?.recordSuccess(account.id, kind = "upload")
                return
            } catch (e: WifiPauseRequiredException) {
                abandonAttempt(current.id, attemptNo)
                pause(jobId)
                db.transferJobDao().getById(jobId)?.let {
                    db.transferJobDao().update(it.copy(errorCode = UploadErrorCode.WIFI_REQUIRED, updatedAt = clock()))
                }
                return
            } catch (e: Throwable) {
                val code = errorCodeOf(e)
                finishAttempt(current.id, attemptNo, UploadAttemptResult.ERROR, code)
                health?.recordFailure(account.id, code, kind = "upload")
                if (code == UploadErrorCode.CHUNK_HASH_MISMATCH && current.remoteId != null) {
                    // Corrupt remote copy: delete it, re-upload from scratch, re-verify.
                    runCatching { connector.deleteObject(tokens.validTokens(account.id), current.remoteId!!) }
                    current = current.copy(status = PlacementStatus.PLANNED, remoteId = null, uploadedAt = null)
                    db.chunkPlacementDao().update(current)
                    if (attemptNo >= MAX_ATTEMPTS) {
                        db.chunkPlacementDao().update(current.copy(status = PlacementStatus.FAILED))
                        throw UploadJobException(code, e)
                    }
                    uploadWithRetries(file, chunk, current, source, jobId)
                    current = db.chunkPlacementDao().getById(current.id)!!
                    continue
                }
                if (!isRetryable(code) || attemptNo >= MAX_ATTEMPTS) {
                    db.chunkPlacementDao().update(current.copy(status = PlacementStatus.FAILED))
                    throw UploadJobException(code, e)
                }
                delayFn(BACKOFF_MS[attemptNo - 1])
            }
        }
    }

    // ---- helpers ----

    /**
     * specs/06 §1: a mirror job is queued when mirroring is on AND at least two
     * connected accounts of the file's provider exist. One account → no mirror.
     */
    private suspend fun enqueueMirrorJobIfEligible(fileId: String) {
        if (!settings.mirrorEnabled) return
        val placements = db.chunkPlacementDao().listByFile(fileId)
        val providers = placements.mapNotNull { placement ->
            db.providerAccountDao().getById(placement.accountId)?.provider
        }.toSet()
        if (providers.isEmpty()) return
        val connected = db.providerAccountDao().getAll()
            .filter { it.status == ProviderAccountStatus.CONNECTED && it.provider in providers }
        val sameProviderCount = providers.maxOf { provider -> connected.count { it.provider == provider } }
        if (sameProviderCount < 2) return
        val now = clock()
        db.transferJobDao().insert(
            TransferJobEntity(
                id = UUID.randomUUID().toString(),
                kind = JobKind.MIRROR,
                fileId = fileId,
                status = JobStatus.QUEUED,
                errorCode = null,
                attempts = 0,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private suspend fun jobStillRunning(jobId: String): Boolean =
        db.transferJobDao().getById(jobId)?.status == JobStatus.RUNNING

    /** Reads exactly the chunk's bytes; a short read means the file changed. */
    private fun readChunk(source: UploadFileSource, file: FileEntity, chunk: FileChunkEntity): ByteArray {
        val offset = chunk.chunkIndex.toLong() * ChunkMath.chunkSizeBytes(file.chunkSizeMib)
        source.openStream().use { stream ->
            var skipped = 0L
            while (skipped < offset) {
                val step = stream.skip(offset - skipped)
                if (step <= 0) break
                skipped += step
            }
            if (skipped < offset) throw UploadJobException(UploadErrorCode.FILE_CHANGED_DURING_UPLOAD)
            val bytes = ByteArray(chunk.sizeBytes.toInt())
            var filled = 0
            while (filled < bytes.size) {
                val read = stream.read(bytes, filled, bytes.size - filled)
                if (read < 0) throw UploadJobException(UploadErrorCode.FILE_CHANGED_DURING_UPLOAD)
                filled += read
            }
            return bytes
        }
    }

    private suspend fun newAttempt(placementId: String): Int {
        val attemptNo = db.uploadAttemptDao().listByPlacement(placementId).size + 1
        db.uploadAttemptDao().insert(
            UploadAttemptEntity(
                id = UUID.randomUUID().toString(),
                placementId = placementId,
                attemptNo = attemptNo,
                startedAt = clock(),
                finishedAt = null,
                result = null,
                errorCode = null,
            ),
        )
        db.uploadAttemptDao().deleteOlderThan(clock() - ATTEMPT_RETENTION_MS)
        return attemptNo
    }

    private suspend fun finishAttempt(placementId: String, attemptNo: Int, result: String, errorCode: String?) {
        db.uploadAttemptDao().listByPlacement(placementId)
            .firstOrNull { it.attemptNo == attemptNo }
            ?.let { row ->
                db.uploadAttemptDao().update(row.copy(finishedAt = clock(), result = result, errorCode = errorCode))
            }
    }

    private suspend fun abandonAttempt(placementId: String, attemptNo: Int) {
        db.uploadAttemptDao().listByPlacement(placementId)
            .firstOrNull { it.attemptNo == attemptNo }
            ?.let { row -> db.uploadAttemptDao().update(row.copy(finishedAt = clock(), result = null)) }
    }

    private suspend fun failJob(jobId: String, errorCode: String) {
        db.transferJobDao().getById(jobId)?.let { job ->
            db.transferJobDao().update(job.copy(status = JobStatus.FAILED, errorCode = errorCode, updatedAt = clock()))
            db.reservationDao().deleteByJob(jobId)
            job.fileId?.let { fileId ->
                db.fileDao().getById(fileId)?.let { file ->
                    db.fileDao().update(file.copy(status = FileStatus.FAILED, updatedAt = clock()))
                }
            }
        }
        notify(NotificationType.UPLOAD_FAILED, """{"code":"$errorCode"}""")
    }

    private suspend fun notify(type: String, payload: String) {
        db.notificationDao().insert(
            NotificationEntity(
                id = UUID.randomUUID().toString(),
                type = type,
                payload = payload,
                read = 0,
                createdAt = clock(),
            ),
        )
        db.notificationDao().trimTo(100)
    }

    private suspend fun dedupName(folderId: String, name: String): String {
        if (db.fileDao().countByNameInFolder(folderId, name) == 0) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while (db.fileDao().countByNameInFolder(folderId, "$base ($n)$ext") != 0) n++
        return "$base ($n)$ext"
    }

    private fun errorCodeOf(e: Throwable): String = when (e) {
        is UploadJobException -> e.errorCode
        is ProviderException -> when (e.code) {
            ProviderErrorCode.PROVIDER_DOWN -> UploadErrorCode.PROVIDER_DOWN
            ProviderErrorCode.PROVIDER_RATE_LIMITED -> UploadErrorCode.PROVIDER_RATE_LIMITED
            ProviderErrorCode.AUTH_EXPIRED -> UploadErrorCode.AUTH_EXPIRED
            else -> UploadErrorCode.UNKNOWN
        }
        is IOException -> UploadErrorCode.NETWORK_UNREACHABLE
        else -> UploadErrorCode.UNKNOWN
    }

    private fun isRetryable(code: String): Boolean = code in setOf(
        UploadErrorCode.NETWORK_UNREACHABLE,
        UploadErrorCode.PROVIDER_DOWN,
        UploadErrorCode.PROVIDER_RATE_LIMITED,
        UploadErrorCode.CHUNK_HASH_MISMATCH,
    )

    private fun jsonEscape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    private fun chunkSizesFor(sizeBytes: Long, chunkSizeMib: Int): List<Long> =
        (0 until ChunkMath.chunkCount(sizeBytes, chunkSizeMib)).map { ChunkMath.chunkSizeAt(sizeBytes, chunkSizeMib, it) }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val MAX_ATTEMPTS = 5
        val BACKOFF_MS = longArrayOf(5_000L, 15_000L, 60_000L, 5 * 60_000L)
        const val ATTEMPT_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        const val READ_BUFFER = 256 * 1024
    }
}
