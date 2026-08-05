package com.noklishare.smartphone.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Devuelve un [Context] cuyos recursos (strings, formatos, etc.) se
 * resuelven en el idioma [locale], de forma independiente al idioma
 * configurado en el sistema.
 *
 * Debe aplicarse en [android.app.Activity.attachBaseContext] para que
 * toda la interfaz se infle ya con el idioma elegido por el usuario.
 \*/
fun Context.applyAppLocale(locale: Locale): Context {
    Locale.setDefault(locale)

    val configuration = Configuration(resources.configuration)
    configuration.setLocale(locale)
    configuration.setLayoutDirection(locale)

    return createConfigurationContext(configuration)
}