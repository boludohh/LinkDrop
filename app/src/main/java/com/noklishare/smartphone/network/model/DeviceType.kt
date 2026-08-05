package com.noklishare.smartphone.network.model

/**
 * Representa el tipo de dispositivo detectado dentro de la red LinkDrop,
 * ya sea local o remoto.
 *
 * Este valor es exclusivamente metadata interna de la aplicación: se
 * utiliza únicamente para decidir qué ícono representativo mostrar en la
 * interfaz (por ejemplo, la silueta de un teléfono, una tablet o un
 * televisor). Nunca se muestra como texto directo al usuario.
 */
enum class DeviceType {
    PHONE,
    TABLET,
    TV;

    companion object {
        /**
         * Convierte el nombre serializado de un [DeviceType] (tal como se
         * transmite en la red mediante NSD) de vuelta a su valor correspondiente.
         * Si el valor no es reconocido o está ausente, se asume [PHONE] como
         * valor seguro por defecto.
         */
        fun fromSerializedName(name: String?): DeviceType {
            return entries.find { it.name == name } ?: PHONE
        }
    }
}