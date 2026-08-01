package com.linkdrop.smartphone.network.util

import android.os.Build

/**
 * Resuelve el nombre bajo el cual este dispositivo se publica en la red local.
 *
 * Actualmente devuelve el modelo del dispositivo de forma automática, ya que
 * todavía no existe una pantalla de Ajustes donde el usuario pueda definir un
 * nombre propio. Cuando esa pantalla exista, esta función deberá reemplazarse
 * para leer el nombre configurado por el usuario (por ejemplo desde
 * SharedPreferences o DataStore), sin necesidad de modificar a quienes la consumen.
 */
fun resolveLocalDeviceName(): String {
    return Build.MODEL ?: "Dispositivo LinkDrop"
}