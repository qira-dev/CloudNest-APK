package com.qiradev.cloudnest.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.SettingsKeys
import com.qiradev.cloudnest.data.entity.AuditEventEntity
import com.qiradev.cloudnest.data.entity.AuditEventType
import com.qiradev.cloudnest.data.entity.SettingsEntity
import com.qiradev.cloudnest.background.Notifier
import com.qiradev.cloudnest.engine.security.Backup
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Settings screen state (specs/09 screen 13). */
data class SettingsUiState(
    val wifiOnlyUpload: Boolean = true,
    val chunkSizeMib: Int = 64,
    val routingStrategy: String = "capacity_aware",
    val mirrorEnabled: Boolean = true,
    val trashRetentionDays: Int = 7,
    val backupReminderEnabled: Boolean = true,
    val lastBackupAt: Long = 0L,
    val themeMode: String = "system",
    val language: String = "en",
    val phoneNotify: Map<String, Boolean> = emptyMap(),
    val unlockConfigured: Boolean = false,
)

/** Backup/unlock outcome surfaced to the screen. */
sealed interface BackupUiEvent {
    data class ExportDone(val bytes: Long) : BackupUiEvent
    data object RestoreDone : BackupUiEvent
    data object UnlockChanged : BackupUiEvent
    data class BackupFailed(val reason: String) : BackupUiEvent
}

/**
 * Drives the settings screen (specs/09 §13). Every write lands in the
 * `settings` table and is audited as `settings_changed` (specs/01
 * §1.2/§1.13). Backup export/import wrap [Backup]; a successful restore asks
 * the screen to restart the process — in-memory graph state (notification
 * relay, controllers) holds stale references to the replaced database.
 */
class SettingsUiController(
    private val db: CloudNestDatabase,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val unlock: com.qiradev.cloudnest.engine.security.UnlockManager? = null,
) {

    var state: SettingsUiState by mutableStateOf(SettingsUiState())
        private set

    var busy: Boolean by mutableStateOf(false)
        private set

    /** One-shot backup outcome for the screen to show/reset. */
    var event: BackupUiEvent? by mutableStateOf(null)
        private set

    fun clearEvent() {
        event = null
    }

    fun load() {
        scope.launch {
            state = readState()
        }
    }

    fun setWifiOnlyUpload(enabled: Boolean) = write(SettingsKeys.WIFI_ONLY_UPLOAD, enabled.toString()) {
        copy(wifiOnlyUpload = enabled)
    }

    fun setChunkSizeMib(sizeMib: Int) = write(SettingsKeys.CHUNK_SIZE_MIB, sizeMib.toString()) {
        copy(chunkSizeMib = sizeMib)
    }

    fun setMirrorEnabled(enabled: Boolean) = write(SettingsKeys.MIRROR_ENABLED, enabled.toString()) {
        copy(mirrorEnabled = enabled)
    }

    fun setTrashRetentionDays(days: Int) = write(SettingsKeys.TRASH_RETENTION_DAYS, days.toString()) {
        copy(trashRetentionDays = days)
    }

    fun setBackupReminderEnabled(enabled: Boolean) =
        write(BACKUP_REMINDER_KEY, enabled.toString()) { copy(backupReminderEnabled = enabled) }

    /** specs/02 §3 + specs/09 §13: capacity-aware vs balanced routing. */
    fun setRoutingStrategy(strategy: String) = write(SettingsKeys.ROUTING_STRATEGY, strategy) {
        copy(routingStrategy = strategy)
    }

    /** specs/09 §13: theme follows the phone by default; manual override allowed. */
    fun setThemeMode(mode: String) = write(SettingsKeys.THEME_MODE, mode) { copy(themeMode = mode) }

    /** specs/09 §13: 9 languages; the caller recreates the activity to apply. */
    fun setLanguage(tag: String, onChanged: () -> Unit) {
        write(SettingsKeys.LANGUAGE, tag) { copy(language = tag) }
        onChanged()
    }

    /** specs/05 §2.2 + specs/09 §13: per-type "ফোনে দেখাও" toggle. */
    fun setPhoneNotify(type: String, enabled: Boolean) =
        write(Notifier.phoneNotifyKey(type), enabled.toString()) {
            copy(phoneNotify = phoneNotify + (type to enabled))
        }

    /** specs/09 §13 security group: change the unlock password (old verified first). */
    fun changeUnlock(oldPassword: String, newPassword: String) {
        val manager = unlock ?: return
        scope.launch {
            try {
                when (manager.verify(oldPassword)) {
                    com.qiradev.cloudnest.engine.security.UnlockResult.Unlocked -> {}
                    else -> {
                        event = BackupUiEvent.BackupFailed("wrong_password")
                        return@launch
                    }
                }
                manager.setUnlockPassword(newPassword)
                event = BackupUiEvent.UnlockChanged
            } catch (e: Exception) {
                event = BackupUiEvent.BackupFailed(e.message ?: "unlock_failed")
            }
        }
    }

    /**
     * Export (specs/08 §4.2): the complete DB goes to the SAF-picked location;
     * the warning text lives on the screen. The stored timestamp feeds the
     * weekly reminder.
     */
    fun exportBackup(context: Context, target: Uri) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val out = context.contentResolver.openOutputStream(target)
                    ?: error("cannot open export target")
                val bytes = out.use { Backup.exportTo(context, db, it) }
                db.settingsDao().upsert(SettingsEntity(LAST_BACKUP_AT_KEY, clock().toString()))
                state = readState()
                event = BackupUiEvent.ExportDone(bytes)
            } catch (e: Exception) {
                event = BackupUiEvent.BackupFailed(e.message ?: "export_failed")
            } finally {
                busy = false
            }
        }
    }

    /**
     * Restore (specs/08 §4.2): validate → replace → reopen → accounts "needs
     * login" → audit. On success the screen must restart the process (see
     * [Backup.replaceDatabase]) — this returns via [BackupUiEvent.RestoreDone].
     */
    fun restoreBackup(context: Context, source: Uri) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val input = context.contentResolver.openInputStream(source)
                    ?: error("cannot open backup file")
                val staged = input.use { Backup.stageBackup(context, it) }
                Backup.replaceDatabase(context, staged)
                val fresh = com.qiradev.cloudnest.data.DatabaseProvider.get(context)
                Backup.markAccountsNeedLogin(fresh, clock())
                state = readStateOn(fresh)
                event = BackupUiEvent.RestoreDone
            } catch (e: Exception) {
                event = BackupUiEvent.BackupFailed(e.message ?: "restore_failed")
            } finally {
                busy = false
            }
        }
    }

    private fun write(key: String, value: String, reduce: SettingsUiState.() -> SettingsUiState) {
        scope.launch {
            db.settingsDao().upsert(SettingsEntity(key, value))
            db.auditEventDao().insert(
                AuditEventEntity(
                    id = UUID.randomUUID().toString(),
                    type = AuditEventType.SETTINGS_CHANGED,
                    detailJson = """{"key":"$key"}""",
                    createdAt = clock(),
                ),
            )
            state = state.reduce()
        }
    }

    private suspend fun readState(): SettingsUiState = readStateOn(db)

    private suspend fun readStateOn(target: CloudNestDatabase): SettingsUiState {
        val dao = target.settingsDao()
        return SettingsUiState(
            wifiOnlyUpload = dao.getValue(SettingsKeys.WIFI_ONLY_UPLOAD)?.toBooleanStrictOrNull() ?: true,
            chunkSizeMib = dao.getValue(SettingsKeys.CHUNK_SIZE_MIB)?.toIntOrNull() ?: 64,
            routingStrategy = dao.getValue(SettingsKeys.ROUTING_STRATEGY)
                ?: SettingsKeys.Defaults.ROUTING_STRATEGY,
            mirrorEnabled = dao.getValue(SettingsKeys.MIRROR_ENABLED)?.toBooleanStrictOrNull() ?: true,
            trashRetentionDays = dao.getValue(SettingsKeys.TRASH_RETENTION_DAYS)?.toIntOrNull() ?: 7,
            backupReminderEnabled = dao.getValue(BACKUP_REMINDER_KEY)?.toBooleanStrictOrNull() ?: true,
            lastBackupAt = dao.getValue(LAST_BACKUP_AT_KEY)?.toLongOrNull() ?: 0L,
            themeMode = dao.getValue(SettingsKeys.THEME_MODE) ?: "system",
            language = dao.getValue(SettingsKeys.LANGUAGE) ?: "en",
            phoneNotify = PHONE_NOTIFY_TYPES.associateWith { type ->
                dao.getValue(Notifier.phoneNotifyKey(type))?.toBooleanStrictOrNull()
                    ?: Notifier.showsOnPhone(type)
            },
            unlockConfigured = dao.getValue(SettingsKeys.UNLOCK_HASH) != null,
        )
    }

    companion object {
        /**
         * specs/08 §4.3 — the weekly backup reminder (on/off). Not part of the
         * specs/01 §1.2 key list; lives beside the spec-listed keys as the
         * reminder's own state, same as the step-7 battery flag.
         */
        const val BACKUP_REMINDER_KEY = "backup_reminder_enabled"
        const val LAST_BACKUP_AT_KEY = "last_backup_at"

        /** Event types the phone-policy toggles cover (specs/05 §2.2 table). */
        val PHONE_NOTIFY_TYPES = listOf(
            com.qiradev.cloudnest.data.entity.NotificationType.UPLOAD_DONE,
            com.qiradev.cloudnest.data.entity.NotificationType.UPLOAD_FAILED,
            com.qiradev.cloudnest.data.entity.NotificationType.MIRROR_FAILED,
            com.qiradev.cloudnest.data.entity.NotificationType.QUOTA_STALE,
            com.qiradev.cloudnest.data.entity.NotificationType.TRASH_PURGED,
            com.qiradev.cloudnest.data.entity.NotificationType.TOKEN_EXPIRED,
        )
    }
}
