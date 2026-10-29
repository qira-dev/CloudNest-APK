package com.qiradev.cloudnest.ads

import android.app.Activity
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.SettingsKeys
import com.qiradev.cloudnest.data.entity.SettingsEntity
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * UMP consent flow skeleton (specs/07 §4): the first launch asks Google's
 * consent SDK once; the result time lands in `settings.admob_consent_given_at`
 * (specs/01 §1.2). Everything is guarded — a device without Google services
 * (plain emulator) simply reports no consent and the app continues; ads fail
 * to load silently elsewhere.
 */
object ConsentManager {

    fun requestConsent(
        activity: Activity,
        db: CloudNestDatabase,
        scope: CoroutineScope,
        onDone: (Boolean) -> Unit = {},
    ) {
        val consentInformation: ConsentInformation = try {
            UserMessagingPlatform.getConsentInformation(activity)
        } catch (e: Throwable) {
            onDone(false)
            return
        }
        runCatching {
            consentInformation.requestConsentInfoUpdate(
                activity,
                ConsentRequestParameters.Builder().build(),
                {
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                        val granted = formError == null && consentInformation.canRequestAds()
                        if (granted) {
                            scope.launch {
                                db.settingsDao().upsert(
                                    SettingsEntity(
                                        SettingsKeys.ADMOB_CONSENT_GIVEN_AT,
                                        System.currentTimeMillis().toString(),
                                    ),
                                )
                                db.auditEventDao().insert(
                                    com.qiradev.cloudnest.data.entity.AuditEventEntity(
                                        id = UUID.randomUUID().toString(),
                                        type = com.qiradev.cloudnest.data.entity.AuditEventType.SETTINGS_CHANGED,
                                        detailJson = """{"key":"admob_consent_given_at"}""",
                                        createdAt = System.currentTimeMillis(),
                                    ),
                                )
                            }
                        }
                        onDone(granted)
                    }
                },
                { onDone(false) },
            )
        }.onFailure { onDone(false) }
    }
}
