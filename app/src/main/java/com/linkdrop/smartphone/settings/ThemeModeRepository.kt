package com.linkdrop.smartphone.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** DataStore exclusivo del modo de tema, separado del resto de ajustes. \*/
private val Context.themeDataStore by preferencesDataStore(name = "linkdrop_theme")

/**
 * Encargado exclusivo de leer y guardar el modo de tema elegido por el
 * usuario (claro, oscuro o seguir al sistema).
 *
 * Esta clase no decide los colores de la interfaz: solo persiste la
 * preferencia y la expone como [Flow] para que el tema la observe.
 \*/
class ThemeModeRepository(private val context: Context) {

    companion object {
        private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
    }

    /** Modo de tema actualmente guardado. Por defecto sigue al sistema. \*/
    val themeMode: Flow<ThemeMode> = context.themeDataStore.data.map { preferences ->
        val rawValue = preferences[THEME_MODE_KEY]
        ThemeMode.entries.find { it.name == rawValue } ?: ThemeMode.SYSTEM
    }

    /** Guarda [mode] como el modo de tema elegido por el usuario. \*/
    suspend fun setThemeMode(mode: ThemeMode) {
        context.themeDataStore.edit { preferences ->
            preferences[THEME_MODE_KEY] = mode.name
        }
    }
}