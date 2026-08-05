package com.noklishare.smartphone.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** DataStore de preferencias de la aplicación, con ámbito a nivel de Context. */
private val Context.settingsDataStore by preferencesDataStore(name = "linkdrop_settings")

/**
 * Encargado exclusivo de leer y guardar el nombre de dispositivo personalizado
 * que el usuario puede definir de forma opcional desde Ajustes.
 *
 * Si el usuario nunca definió un nombre propio, [customDeviceName] emite `null`,
 * y quien consuma este repositorio debe recurrir a un valor por defecto
 * (por ejemplo, el nombre automático resuelto por
 * [com.linkdrop.smartphone.network.util.resolveLocalDeviceName]).
 *
 * Esta clase no decide cuál es el nombre final a usar: esa decisión
 * corresponde a quien la consuma.
 */
class DeviceNameRepository(private val context: Context) {

    companion object {
        private val CUSTOM_DEVICE_NAME_KEY = stringPreferencesKey("custom_device_name")
    }

    /** Nombre personalizado guardado por el usuario, o `null` si nunca definió uno. */
    val customDeviceName: Flow<String?> = context.settingsDataStore.data.map { preferences ->
        preferences[CUSTOM_DEVICE_NAME_KEY]?.takeIf { it.isNotBlank() }
    }

    /**
     * Guarda [name] como el nombre de dispositivo personalizado del usuario.
     * Si [name] está vacío o en blanco, se elimina la preferencia guardada,
     * volviendo a depender del nombre automático por defecto.
     */
    suspend fun setCustomDeviceName(name: String) {
        context.settingsDataStore.edit { preferences ->
            if (name.isBlank()) {
                preferences.remove(CUSTOM_DEVICE_NAME_KEY)
            } else {
                preferences[CUSTOM_DEVICE_NAME_KEY] = name.trim()
            }
        }
    }
}