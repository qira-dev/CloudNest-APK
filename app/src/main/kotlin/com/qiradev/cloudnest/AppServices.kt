package com.qiradev.cloudnest

import android.content.Context
import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.DatabaseProvider
import com.qiradev.cloudnest.data.entity.Providers
import com.qiradev.cloudnest.engine.archive.ArchiveService
import com.qiradev.cloudnest.engine.health.HealthTracker
import com.qiradev.cloudnest.engine.mirror.MirrorService
import com.qiradev.cloudnest.engine.providers.AccountConnectionManager
import com.qiradev.cloudnest.engine.providers.AesGcmKeystoreCipher
import com.qiradev.cloudnest.engine.providers.ProviderConnector
import com.qiradev.cloudnest.engine.providers.ProviderErrorCode
import com.qiradev.cloudnest.engine.providers.ProviderException
import com.qiradev.cloudnest.engine.providers.OAuthStateStore
import com.qiradev.cloudnest.engine.providers.TokenPair
import com.qiradev.cloudnest.engine.providers.TokenStore
import com.qiradev.cloudnest.engine.providers.google.GoogleDriveConfig
import com.qiradev.cloudnest.engine.providers.google.GoogleDriveConnector
import com.qiradev.cloudnest.engine.upload.TokenProvider
import com.qiradev.cloudnest.engine.upload.UploadService

/**
 * Single place where the production engine graph is assembled (specs/10 §4):
 * the database, provider connectors and token plumbing. UI screens (specs/09)
 * and background workers (specs/05) share one graph per process.
 *
 * `clientId` stays empty until the owner registers the Android OAuth client
 * (specs/04 §3.1) — connect flows fail honestly until then; engine work that
 * only needs already-connected accounts keeps running.
 */
object AppServices {

    const val OAUTH_REDIRECT_URI = "cloudnest://oauth"

    fun db(context: Context): CloudNestDatabase = DatabaseProvider.get(context)

    fun connectors(): Map<String, ProviderConnector> = mapOf(
        Providers.GOOGLE_DRIVE to GoogleDriveConnector(
            GoogleDriveConfig(clientId = "", redirectUri = OAUTH_REDIRECT_URI),
        ),
    )

    fun tokenStore(): TokenStore = TokenStore(AesGcmKeystoreCipher())

    /** Production TokenProvider: decrypt the account's stored tokens on each use. */
    fun tokenProvider(db: CloudNestDatabase, store: TokenStore): TokenProvider =
        TokenProvider { accountId ->
            val account = db.providerAccountDao().getById(accountId)
                ?: throw ProviderException(ProviderErrorCode.AUTH_EXPIRED)
            val blob = account.encryptedTokens
                ?: throw ProviderException(ProviderErrorCode.AUTH_EXPIRED)
            val stored = store.restore(blob)
            TokenPair(stored.accessToken, stored.refreshToken, stored.expiresAtMs)
        }

    fun archiveService(db: CloudNestDatabase): ArchiveService =
        ArchiveService(db, connectors(), tokenProvider(db, tokenStore()))

    fun connectionManager(db: CloudNestDatabase): AccountConnectionManager =
        AccountConnectionManager(db, tokenStore(), connectors(), OAuthStateStore(), health())

    /**
     * One health ledger per process (specs/06 §2.1) — memory state, replayed
     * from upload_attempts at startup by the background layer (specs/05).
     */
    @Volatile
    private var healthSingleton: HealthTracker? = null

    fun health(): HealthTracker =
        healthSingleton ?: synchronized(this) {
            healthSingleton ?: HealthTracker().also { healthSingleton = it }
        }

    fun mirrorService(db: CloudNestDatabase): MirrorService =
        MirrorService(db, connectors(), tokenProvider(db, tokenStore()), health())

    fun uploadService(db: CloudNestDatabase): UploadService =
        UploadService(db, connectors(), tokenProvider(db, tokenStore()), health = health())
}
