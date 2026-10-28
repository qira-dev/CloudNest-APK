package com.qiradev.cloudnest.engine.mirror

import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.entity.ChunkPlacementEntity
import com.qiradev.cloudnest.data.entity.FileChunkEntity
import com.qiradev.cloudnest.data.entity.FileStatus
import com.qiradev.cloudnest.data.entity.JobKind
import com.qiradev.cloudnest.data.entity.JobStatus
import com.qiradev.cloudnest.data.entity.NotificationEntity
import com.qiradev.cloudnest.data.entity.NotificationType
import com.qiradev.cloudnest.data.entity.PlacementRole
import com.qiradev.cloudnest.data.entity.PlacementStatus
import com.qiradev.cloudnest.data.entity.ProviderAccountEntity
import com.qiradev.cloudnest.data.entity.ProviderAccountStatus
import com.qiradev.cloudnest.data.entity.ReservationEntity
import com.qiradev.cloudnest.data.entity.TransferJobEntity
import com.qiradev.cloudnest.engine.health.Health
import com.qiradev.cloudnest.engine.health.HealthTracker
import com.qiradev.cloudnest.engine.providers.ProviderConnector
import com.qiradev.cloudnest.engine.providers.ProviderErrorCode
import com.qiradev.cloudnest.engine.providers.ProviderException
import com.qiradev.cloudnest.engine.upload.Capacity
import com.qiradev.cloudnest.engine.upload.Hashing
import com.qiradev.cloudnest.engine.upload.NetworkGate
import com.qiradev.cloudnest.engine.upload.NetworkGate.AlwaysAllowed
import com.qiradev.cloudnest.engine.upload.RoutingStrategy
import com.qiradev.cloudnest.engine.upload.TokenProvider
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.delay

/** Safe error codes for mirror failures (specs/02 §8 style). */
object MirrorErrorCode {
    const val CHUNK_UNAVAILABLE = "chunk_unavailable"
    const val NO_MIRROR_ACCOUNT = "no_mirror_account"
    const val NO_FREE_SPACE = "no_free_space"
    const val CHUNK_HASH_MISMATCH = "chunk_hash_mismatch"
    const val NETWORK_UNREACHABLE = "network_unreachable"
    const val PROVIDER_DOWN = "provider_down"
    const val AUTH_EXPIRED = "auth_expired"
    const val UNKNOWN = "unknown"
}

class MirrorJobException(val errorCode: String, cause: Throwable? = null) : Exception(errorCode, cause)

/**
 * Chunk mirroring (specs/06 §1): a second copy of every chunk on ANOTHER
 * account of the SAME provider. Runs as its own `kind=mirror` transfer job
 * after the upload completes — mirroring never holds up an upload. The source
 * copy is the primary placement; every mirror placement is written verified.
 * A failed chunk leaves the file fully usable (the primary copy is intact);
 * "মিরর সম্পূর্ণ করো" re-enqueues the job.
 */
class MirrorService(
    private val db: CloudNestDatabase,
    private val connectors: Map<String, ProviderConnector>,
    private val tokens: TokenProvider,
    private val health: HealthTracker? = null,
    private val networkGate: NetworkGate = AlwaysAllowed,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Queues a mirror job for a complete file; no-op when one is already pending. */
    suspend fun enqueueJob(fileId: String): String? {
        val file = db.fileDao().getById(fileId) ?: return null
        if (file.status != FileStatus.COMPLETE) return null
        // specs/06 §1.1: at least two same-provider accounts must exist.
        val placements = db.chunkPlacementDao().listByFile(fileId)
        val providers = placements.mapNotNull { placement ->
            db.providerAccountDao().getById(placement.accountId)?.provider
        }.toSet()
        val connected = db.providerAccountDao().getAll()
            .filter { it.status == ProviderAccountStatus.CONNECTED && it.provider in providers }
        val sameProviderCount = providers.maxOfOrNull { provider -> connected.count { it.provider == provider } } ?: 0
        if (sameProviderCount < 2) return null
        val pending = JobStatus.QUEUED to JobStatus.RUNNING
        val exists = (db.transferJobDao().listByKindAndStatus(JobKind.MIRROR, pending.first) +
            db.transferJobDao().listByKindAndStatus(JobKind.MIRROR, pending.second))
            .any { it.fileId == fileId }
        if (exists) return null
        val now = clock()
        val jobId = UUID.randomUUID().toString()
        db.transferJobDao().insert(
            TransferJobEntity(
                id = jobId,
                kind = JobKind.MIRROR,
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

    /** Runs one queued mirror job to completion (or failure). */
    suspend fun runJob(jobId: String) {
        val initial = db.transferJobDao().getById(jobId) ?: return
        if (initial.status != JobStatus.QUEUED) return
        db.transferJobDao().update(initial.copy(status = JobStatus.RUNNING, attempts = initial.attempts + 1, updatedAt = clock()))

        val file = db.transferJobDao().getById(jobId)!!.fileId?.let { db.fileDao().getById(it) }
        if (file == null || file.status != FileStatus.COMPLETE) {
            failJob(jobId, MirrorErrorCode.UNKNOWN)
            return
        }
        // Wi-Fi-only policy: mirror traffic waits like upload traffic.
        if (!networkGate.isSatisfied()) {
            db.transferJobDao().getById(jobId)?.let {
                db.transferJobDao().update(it.copy(status = JobStatus.QUEUED, updatedAt = clock()))
            }
            return
        }

        try {
            val chunks = db.fileChunkDao().listByFile(file.id)
            for (chunk in chunks) {
                if (db.transferJobDao().getById(jobId)?.status != JobStatus.RUNNING) return
                mirrorChunk(chunk, jobId)
            }
            val finished = db.transferJobDao().getById(jobId)!!
            if (finished.status == JobStatus.RUNNING) {
                db.transferJobDao().update(finished.copy(status = JobStatus.DONE, errorCode = null, updatedAt = clock()))
                val name = db.fileDao().getById(file.id)?.name ?: ""
                notify(NotificationType.MIRROR_DONE, """{"file":"${jsonEscape(name)}"}""")
            }
        } catch (e: MirrorJobException) {
            failJob(jobId, e.errorCode)
        } finally {
            db.reservationDao().deleteByJob(jobId)
        }
    }

    /** Failed mirror job goes back to queued (the "মিরর সম্পূর্ণ করো" retry path). */
    suspend fun retry(jobId: String) {
        val job = db.transferJobDao().getById(jobId) ?: return
        if (job.status == JobStatus.FAILED) {
            db.transferJobDao().update(job.copy(status = JobStatus.QUEUED, errorCode = null, updatedAt = clock()))
        }
    }

    /** Startup recovery: a mirror job nobody is driving goes back to queued. */
    suspend fun recoverAtStartup() {
        db.reservationDao().deleteForInactiveJobs()
        db.reservationDao().deleteOrphaned()
        db.transferJobDao().listByKindAndStatus(JobKind.MIRROR, JobStatus.RUNNING).forEach { job ->
            db.transferJobDao().update(job.copy(status = JobStatus.QUEUED, updatedAt = clock()))
        }
    }

    // ---- per chunk ----

    private suspend fun mirrorChunk(chunk: FileChunkEntity, jobId: String) {
        val placements = db.chunkPlacementDao().listByChunk(chunk.id)
        val primary = placements.firstOrNull {
            it.role == PlacementRole.PRIMARY && it.remoteId != null &&
                it.status in listOf(PlacementStatus.VERIFIED, PlacementStatus.UPLOADED)
        } ?: throw MirrorJobException(MirrorErrorCode.CHUNK_UNAVAILABLE)

        if (placements.any { it.role == PlacementRole.MIRROR && it.status == PlacementStatus.VERIFIED }) return

        var attempt = 1
        while (true) {
            val account = chooseMirrorAccount(primary.accountId, chunk.sizeBytes)
                ?: throw MirrorJobException(MirrorErrorCode.NO_MIRROR_ACCOUNT)
            val reservationId = UUID.randomUUID().toString()
            db.reservationDao().insert(
                ReservationEntity(reservationId, account.id, chunk.sizeBytes, jobId, clock()),
            )
            try {
                mirrorTo(account, primary, chunk)
                db.reservationDao().deleteById(reservationId)
                health?.recordSuccess(account.id, kind = "mirror")
                return
            } catch (e: Exception) {
                db.reservationDao().deleteById(reservationId)
                val code = errorCodeOf(e)
                health?.recordFailure(account.id, code, kind = "mirror")
                if (!isRetryable(code) || attempt >= MAX_ATTEMPTS) throw MirrorJobException(code, e)
                delayFn(BACKOFF_MS[attempt - 1])
                attempt++
            }
        }
    }

    private suspend fun mirrorTo(account: ProviderAccountEntity, primary: ChunkPlacementEntity, chunk: FileChunkEntity) {
        val connector = connectors[account.provider]
            ?: throw MirrorJobException(MirrorErrorCode.UNKNOWN)
        // Source: the primary copy, hash-checked before anything is written.
        val primaryAccount = db.providerAccountDao().getById(primary.accountId)
            ?: throw MirrorJobException(MirrorErrorCode.UNKNOWN)
        val primaryConnector = connectors[primaryAccount.provider]
            ?: throw MirrorJobException(MirrorErrorCode.UNKNOWN)
        val bytes = primaryConnector.downloadChunk(tokens.validTokens(primaryAccount.id), primary.remoteId!!)
        if (bytes.size.toLong() != chunk.sizeBytes || !Hashing.sha256(bytes).contentEquals(chunk.sha256.hexToBytes())) {
            throw MirrorJobException(MirrorErrorCode.CHUNK_HASH_MISMATCH)
        }

        val root = account.rootPath ?: connector.ensureRoot(tokens.validTokens(account.id)).also { rootId ->
            db.providerAccountDao().update(account.copy(rootPath = rootId))
        }
        val placementId = existingMirrorPlacementId(chunk.id) ?: UUID.randomUUID().toString()
        val remoteId = connector.uploadChunk(tokens.validTokens(account.id), root, "$placementId.bin", bytes)

        // Verify the mirror copy the same way the upload engine does.
        val readBack = connector.downloadChunk(tokens.validTokens(account.id), remoteId)
        if (readBack.size.toLong() != chunk.sizeBytes || !Hashing.sha256(readBack).contentEquals(chunk.sha256.hexToBytes())) {
            runCatching { connector.deleteObject(tokens.validTokens(account.id), remoteId) }
            throw MirrorJobException(MirrorErrorCode.CHUNK_HASH_MISMATCH)
        }

        val existing = db.chunkPlacementDao().getById(placementId)
        val row = ChunkPlacementEntity(
            id = placementId,
            chunkId = chunk.id,
            accountId = account.id,
            role = PlacementRole.MIRROR,
            remoteId = remoteId,
            status = PlacementStatus.VERIFIED,
            uploadedAt = clock(),
        )
        if (existing == null) db.chunkPlacementDao().insert(row) else db.chunkPlacementDao().update(row)
    }

    /**
     * Mirror account selection (specs/06 §1.1): the capacity rule of specs/02 §3
     * over same-provider connected accounts; the primary's pool is excluded and
     * Down accounts are not chosen.
     */
    private suspend fun chooseMirrorAccount(primaryAccountId: String, chunkBytes: Long): ProviderAccountEntity? {
        val accounts = db.providerAccountDao().getAll().filter { it.status == ProviderAccountStatus.CONNECTED }
        val primary = accounts.firstOrNull { it.id == primaryAccountId } ?: return null
        val sameProvider = accounts.filter { it.provider == primary.provider }
        val outstanding = HashMap<String, Long>()
        db.reservationDao().getAll().forEach { reservation ->
            outstanding.merge(reservation.accountId, reservation.bytes, Long::plus)
        }
        val candidates = Capacity.evaluate(sameProvider, outstanding, reservePerAccountBytes = 0, safetyMarginBytes = 0)
            .filter { capacity ->
                capacity.isEligible &&
                    primaryAccountId !in capacity.memberIds &&
                    capacity.representativeId != primaryAccountId &&
                    capacity.usable.bytesOrMax >= chunkBytes &&
                    health?.verdict(capacity.representativeId) != Health.DOWN
            }
        if (candidates.isEmpty()) return null
        val plan = com.qiradev.cloudnest.engine.upload.Router.route(
            candidates,
            listOf(chunkBytes),
            RoutingStrategy.CAPACITY_AWARE,
        )
        val chosenId = plan.assignments.first()
        return sameProvider.first { it.id == chosenId }
    }

    private suspend fun existingMirrorPlacementId(chunkId: String): String? =
        db.chunkPlacementDao().listByChunk(chunkId)
            .firstOrNull { it.role == PlacementRole.MIRROR }
            ?.id

    private suspend fun failJob(jobId: String, errorCode: String) {
        db.transferJobDao().getById(jobId)?.let { job ->
            db.transferJobDao().update(job.copy(status = JobStatus.FAILED, errorCode = errorCode, updatedAt = clock()))
        }
        db.reservationDao().deleteByJob(jobId)
        val fileId = db.transferJobDao().getById(jobId)?.fileId
        val name = fileId?.let { db.fileDao().getById(it)?.name } ?: ""
        notify(NotificationType.MIRROR_FAILED, """{"file":"${jsonEscape(name)}","code":"$errorCode"}""")
    }

    private suspend fun notify(type: String, payload: String) {
        db.notificationDao().insert(
            NotificationEntity(UUID.randomUUID().toString(), type, payload, read = 0, createdAt = clock()),
        )
        db.notificationDao().trimTo(100)
    }

    private fun errorCodeOf(e: Throwable): String = when (e) {
        is MirrorJobException -> e.errorCode
        is ProviderException -> when (e.code) {
            ProviderErrorCode.PROVIDER_DOWN -> MirrorErrorCode.PROVIDER_DOWN
            ProviderErrorCode.PROVIDER_RATE_LIMITED -> MirrorErrorCode.PROVIDER_DOWN
            ProviderErrorCode.AUTH_EXPIRED -> MirrorErrorCode.AUTH_EXPIRED
            else -> MirrorErrorCode.UNKNOWN
        }
        is IOException -> MirrorErrorCode.NETWORK_UNREACHABLE
        else -> MirrorErrorCode.UNKNOWN
    }

    private fun isRetryable(code: String): Boolean = code in setOf(
        MirrorErrorCode.NETWORK_UNREACHABLE,
        MirrorErrorCode.PROVIDER_DOWN,
        MirrorErrorCode.CHUNK_HASH_MISMATCH,
    )

    private fun jsonEscape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val MAX_ATTEMPTS = 3
        val BACKOFF_MS = longArrayOf(5_000L, 15_000L, 60_000L)
    }
}
