package com.noklishare.smartphone.settings

/**
 * Idiomas soportados por LinkDrop en su primera versión.
 *
 * No existe una opción de "seguir el sistema": el usuario elige
 * explícitamente uno de estos idiomas y la preferencia queda guardada.
 *
 * @property code Código BCP-47 utilizado para resolver los recursos
 * de strings y configurar el locale de la aplicación.
 * @property displayName Nombre del idioma escrito en su propio idioma
 * (endónimo), mostrado tal cual en el selector de Ajustes sin traducirse.
 \*/
enum class AppLanguage(val code: String, val displayName: String) {

    /** Idioma por defecto de la aplicación. \*/
    SPANISH_LATAM("es-419", "Español (Latinoamérica)"),

    SPANISH_SPAIN("es-ES", "Español (España)"),

    ENGLISH("en", "English");

    companion object {
        /**
         * Convierte un código BCP-47 guardado a su [AppLanguage] correspondiente.
         * Si el código es nulo o no reconocido, devuelve el idioma por defecto.
         \*/
        fun fromCode(code: String?): AppLanguage {
            return entries.find { it.code == code } ?: SPANISH_LATAM
        }
    }
}