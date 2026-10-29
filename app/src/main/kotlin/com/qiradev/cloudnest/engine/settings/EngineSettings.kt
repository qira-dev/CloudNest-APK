package com.qiradev.cloudnest.engine.settings

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.SettingsKeys
import com.qiradev.cloudnest.engine.upload.ChunkMath
import com.qiradev.cloudnest.engine.upload.NetworkGate
import com.qiradev.cloudnest.engine.upload.UploadSettings

/**
 * Reads the engine-relevant settings rows (specs/01 §1.2) into the values the
 * engines consume, and applies the spec defaults where a row is missing:
 * Wi-Fi-only true, chunk 64 MiB, mirroring on (specs/02 §1/§5, specs/06 §1.1).
 */
object EngineSettings {

    suspend fun loadUploadSettings(db: CloudNestDatabase): UploadSettings {
        val dao = db.settingsDao()
        return UploadSettings(
            chunkSizeMib = dao.getValue(SettingsKeys.CHUNK_SIZE_MIB)
                ?.toIntOrNull()
                ?.takeIf { ChunkMath.isValidChunkSizeMib(it) }
                ?: ChunkMath.DEFAULT_CHUNK_SIZE_MIB,
            reservePerAccountBytes = dao.getValue(SettingsKeys.RESERVE_PER_ACCOUNT_BYTES)?.toLongOrNull() ?: 0L,
            safetyMarginBytes = dao.getValue(SettingsKeys.SAFETY_MARGIN_BYTES)?.toLongOrNull() ?: 0L,
            mirrorEnabled = dao.getValue(SettingsKeys.MIRROR_ENABLED)?.toBooleanStrictOrNull() ?: true,
        )
    }

    suspend fun wifiOnlyUpload(db: CloudNestDatabase): Boolean =
        db.settingsDao().getValue(SettingsKeys.WIFI_ONLY_UPLOAD)
            ?.toBooleanStrictOrNull() ?: true
}

/**
 * Production [NetworkGate] for the Wi-Fi-only policy (specs/02 §5): with the
 * setting on, only a validated Wi-Fi network lets transfers run; mobile or no
 * network pauses the job (the queue re-drives it when the network returns).
 * The decision logic is a pure function of the [isOnWifi] probe so it is
 * unit-testable without Android shadows.
 */
class WifiOnlyNetworkGate(
    private val wifiOnly: Boolean,
    private val isOnWifi: () -> Boolean,
) : NetworkGate {

    override fun isSatisfied(): Boolean = !wifiOnly || isOnWifi()

    companion object {
        /** Production probe: an active, validated Wi-Fi network. */
        fun fromContext(context: Context, wifiOnly: Boolean): WifiOnlyNetworkGate {
            val connectivity = context.applicationContext
                .getSystemService(ConnectivityManager::class.java)
            return WifiOnlyNetworkGate(wifiOnly) {
                val network = connectivity?.activeNetwork ?: return@WifiOnlyNetworkGate false
                val caps = connectivity.getNetworkCapabilities(network)
                    ?: return@WifiOnlyNetworkGate false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
        }
    }
}
