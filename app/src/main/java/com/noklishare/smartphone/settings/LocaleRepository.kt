package com.noklishare.smartphone.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** DataStore exclusivo del idioma elegido, separado del resto de ajustes. \*/
private val Context.localeDataStore by preferencesDataStore(name = "linkdrop_locale")

/**
 * Encargado exclusivo de leer y guardar el idioma de la aplicación
 * elegido por el usuario.
 *
 * El idioma nunca sigue al sistema: si el usuario no ha elegido uno
 * todavía, se devuelve el idioma por defecto ([AppLanguage.SPANISH_LATAM]).
 *
 * Esta clase no aplica el locale a los recursos: esa responsabilidad
 * corresponde a [com.linkdrop.smartphone.util.applyAppLocale].
 \*/
class LocaleRepository(private val context: Context) {

    companion object {
        private val SELECTED_LANGUAGE_KEY = stringPreferencesKey("selected_language")
    }

    /** Código BCP-47 del idioma actualmente seleccionado. \*/
    val selectedLanguageCode: Flow<String> = context.localeDataStore.data.map { preferences ->
        preferences[SELECTED_LANGUAGE_KEY] ?: AppLanguage.SPANISH_LATAM.code
    }

    /** Guarda [language] como el idioma de la aplicación. \*/
    suspend fun setLanguage(language: AppLanguage) {
        context.localeDataStore.edit { preferences ->
            preferences[SELECTED_LANGUAGE_KEY] = language.code
        }
    }
}