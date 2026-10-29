package com.qiradev.cloudnest.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.qiradev.cloudnest.data.CloudNestDatabase
import com.qiradev.cloudnest.data.entity.SettingsEntity
import com.qiradev.cloudnest.data.SettingsKeys
import com.qiradev.cloudnest.engine.settings.EngineSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * App theme (specs/09 §13): follows the phone's dark/light setting by default;
 * the `theme_mode` setting (specs/01 §1.2) can force light or dark. Colors are
 * the material3 schemes — the visual design itself stays the owner's template
 * decision (specs/09 principle: screenshots refine this later).
 */
@Composable
fun CloudNestTheme(themeMode: String, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        EngineSettings.THEME_DARK -> true
        EngineSettings.THEME_LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

/** The stored theme mode with the spec default. */
suspend fun loadThemeMode(db: CloudNestDatabase): String = EngineSettings.themeMode(db)

/** Writes the theme choice; the app shell re-reads it on the next composition. */
fun setThemeMode(db: CloudNestDatabase, scope: CoroutineScope, mode: String) {
    scope.launch {
        db.settingsDao().upsert(SettingsEntity(SettingsKeys.THEME_MODE, mode))
    }
}

/**
 * Per-app language (specs/09 §13 — 9 languages). The stored `language` tag is
 * applied by wrapping the activity's base context, which works on every API
 * level without the appcompat delegate; API 33+ additionally exposes the
 * choice in the system per-app language settings (localeConfig in the manifest).
 */
object AppLocales {
    /** tags the picker offers — English first (default). */
    val OFFERED = listOf(
        "en" to "English",
        "bn" to "বাংলা",
        "zh" to "中文",
        "es" to "Español",
        "de" to "Deutsch",
        "ru" to "Русский",
        "fr" to "Français",
        "ja" to "日本語",
        "pt" to "Português",
    )

    suspend fun savedTag(db: CloudNestDatabase): String =
        db.settingsDao().getValue(SettingsKeys.LANGUAGE) ?: "en"

    fun wrapContext(context: android.content.Context, tag: String): android.content.Context {
        val locale = java.util.Locale.forLanguageTag(tag)
        java.util.Locale.setDefault(locale)
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLocales(android.os.LocaleList(locale))
        return context.createConfigurationContext(config)
    }

    fun setLanguage(db: CloudNestDatabase, scope: CoroutineScope, tag: String, onChanged: () -> Unit) {
        scope.launch {
            db.settingsDao().upsert(SettingsEntity(SettingsKeys.LANGUAGE, tag))
            onChanged()
        }
    }
}
