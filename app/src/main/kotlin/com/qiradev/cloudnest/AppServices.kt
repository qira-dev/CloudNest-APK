package com.qiradev.cloudnest

import android.content.Context
import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.DatabaseProvider
import com.qiradev.cloudnest.data.entity.Providers
import com.qiradev.cloudnest.engine.archive.ArchiveService
import com.qiradev.cloudnest.engine.diagnostics.DiagnosticsService
import com.qiradev.cloudnest.engine.download.DownloadService
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
import com.qiradev.cloudnest.engine.providers.dropbox.DropboxConfig
import com.qiradev.cloudnest.engine.providers.dropbox.DropboxConnector
import com.qiradev.cloudnest.engine.providers.onedrive.OneDriveConfig
import com.qiradev.cloudnest.engine.providers.onedrive.OneDriveConnector
import com.qiradev.cloudnest.engine.providers.pcloud.PCloudConfig
import com.qiradev.cloudnest.engine.providers.pcloud.PCloudConnector
import com.qiradev.cloudnest.engine.settings.EngineSettings
import com.qiradev.cloudnest.engine.settings.WifiOnlyNetworkGate
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

    /**
     * OAuth flow store (states + PKCE verifiers) backed by the meta table —
     * instance-churn-safe, so a redirect completed by a later composition
     * finds the state the earlier composition minted (specs/04 §2).
     */
    fun oauthFlowStore(db: CloudNestDatabase?): OAuthStateStore = OAuthStateStore(db)

    /**
     * One connector per supported provider (specs/04 §3.1–§3.4). Client ids
     * stay empty until the owner registers each OAuth client — connect flows
     * fail honestly until then; engine work that only needs already-connected
     * accounts keeps running. MEGA is not wired (specs/04 §3.5: risk-accepted,
     * last — open question #2).
     */
    fun connectors(db: CloudNestDatabase? = null): Map<String, ProviderConnector> {
        val flowStore = oauthFlowStore(db)
        return mapOf(
            Providers.GOOGLE_DRIVE to GoogleDriveConnector(
                GoogleDriveConfig(clientId = "", redirectUri = OAUTH_REDIRECT_URI),
            ),
            Providers.DROPBOX to DropboxConnector(
                DropboxConfig(clientId = "", redirectUri = OAUTH_REDIRECT_URI),
                flowStore = flowStore,
            ),
            Providers.ONEDRIVE to OneDriveConnector(
                OneDriveConfig(clientId = "", redirectUri = OAUTH_REDIRECT_URI),
                flowStore = flowStore,
            ),
            Providers.PCLOUD to PCloudConnector(
                PCloudConfig(clientId = "", redirectUri = OAUTH_REDIRECT_URI),
                flowStore = flowStore,
            ),
        )
    }

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
        ArchiveService(db, connectors(db), tokenProvider(db, tokenStore()))

    fun connectionManager(db: CloudNestDatabase): AccountConnectionManager =
        AccountConnectionManager(db, tokenStore(), connectors(db), oauthFlowStore(db), health())

    fun diagnosticsService(db: CloudNestDatabase): DiagnosticsService =
        DiagnosticsService(db, health(), connectionManager(db))

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
        MirrorService(db, connectors(db), tokenProvider(db, tokenStore()), health())

    fun downloadService(db: CloudNestDatabase): DownloadService =
        DownloadService(db, connectors(db), tokenProvider(db, tokenStore()), health = health())

    fun uploadService(db: CloudNestDatabase): UploadService =
        UploadService(db, connectors(db), tokenProvider(db, tokenStore()), health = health())

    /**
     * Settings-aware production services (specs/01 §1.2 + specs/02 §1/§5): the
     * stored chunk size, mirroring toggle and Wi-Fi-only policy actually reach
     * the engines. Transfer drivers (foreground service, revive worker) build
     * these per drain so a settings change applies to the next run.
     */
    suspend fun liveTransferServices(
        context: Context,
        db: CloudNestDatabase,
    ): Pair<UploadService, MirrorService> {
        val gate = WifiOnlyNetworkGate.fromContext(context, EngineSettings.wifiOnlyUpload(db))
        val upload = UploadService(
            db,
            connectors(db),
            tokenProvider(db, tokenStore()),
            networkGate = gate,
            settings = EngineSettings.loadUploadSettings(db),
            health = health(),
        )
        val mirror = MirrorService(db, connectors(db), tokenProvider(db, tokenStore()), health(), networkGate = gate)
        return upload to mirror
    }
}
