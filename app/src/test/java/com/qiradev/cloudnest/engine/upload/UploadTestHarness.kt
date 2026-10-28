package com.qiradev.cloudnest.engine.upload

import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.entity.ProviderAccountEntity
import com.qiradev.cloudnest.data.entity.ProviderAccountStatus
import com.qiradev.cloudnest.data.entity.QuotaStatus
import com.qiradev.cloudnest.data.testFolder
import com.qiradev.cloudnest.engine.providers.FakeConnector
import com.qiradev.cloudnest.engine.providers.TokenPair
import kotlinx.coroutines.runBlocking

/** Shared fixture for the upload-engine service tests — synthetic data only. */
class UploadTestHarness(
    val db: CloudNestDatabase,
    val connector: FakeConnector = FakeConnector(),
    chunkSizeMib: Int = 64,
    reservePerAccountBytes: Long = 0,
    safetyMarginBytes: Long = 0,
) {
    val delays = mutableListOf<Long>()

    /** Closes the network gate once the connector has seen [flipAfterUploads] uploads. */
    var flipAfterUploads = -1

    private var now = 1_000_000L

    val service = UploadService(
        db = db,
        connectors = mapOf("google_drive" to connector),
        tokens = { TokenPair("test-token", null, System.currentTimeMillis() + 3_600_000) },
        networkGate = object : NetworkGate {
            override fun isSatisfied(): Boolean =
                !(flipAfterUploads >= 0 && connector.uploadCount >= flipAfterUploads)
        },
        settings = UploadSettings(
            chunkSizeMib = chunkSizeMib,
            reservePerAccountBytes = reservePerAccountBytes,
            safetyMarginBytes = safetyMarginBytes,
        ),
        delayFn = { delays.add(it) },
        clock = { ++now },
    )

    /** Connects a synthetic account with a `current` quota and prepares the root folder. */
    suspend fun addAccount(id: String, freeBytes: Long, scopeKey: String? = null) = runBlocking {
        db.providerAccountDao().insert(
            ProviderAccountEntity(
                id = id,
                provider = "google_drive",
                label = id,
                status = ProviderAccountStatus.CONNECTED,
                encryptedTokens = null,
                tokenExpiresAt = 0,
                quotaTotalBytes = freeBytes,
                quotaUsedBytes = 0,
                quotaUnlimited = 0,
                quotaStatus = QuotaStatus.CURRENT,
                quotaObservedAt = now,
                quotaScopeKey = scopeKey,
                rootPath = "fake-root",
                createdAt = now,
                lastUsedAt = now,
            ),
        )
        if (db.folderDao().getById("folder-1") == null) {
            db.folderDao().insert(testFolder(id = "folder-1", parent = null, name = ""))
        }
    }

    fun enqueueBlocking(source: UploadFileSource, name: String): String = runBlocking {
        service.enqueue(source, "folder-1", name, "application/octet-stream")
    }

    fun runBlockingJob(jobId: String) = runBlocking { service.runJob(jobId) }
}
