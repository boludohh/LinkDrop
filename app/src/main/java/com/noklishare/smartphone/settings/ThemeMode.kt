package com.noklishare.smartphone.settings

/**
 * Modos de tema disponibles para la interfaz de LinkDrop.
 *
 * A diferencia del idioma, el tema sí puede seguir al sistema,
 * ya que todo dispositivo tiene un modo claro u oscuro activo.
 \*/
enum class ThemeMode {
    /** Fuerza la paleta clara en toda la aplicación. \*/
    LIGHT,

    /** Fuerza la paleta oscura en toda la aplicación. \*/
    DARK,

    /** Usa el modo claro u oscuro según la configuración del sistema. \*/
    SYSTEM
}