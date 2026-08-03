package com.linkdrop.smartphone.network.model

import java.net.InetAddress

/**
 * Representa un dispositivo LinkDrop descubierto en la red local mediante NSD.
 *
 * @property serviceName Nombre del servicio anunciado por el dispositivo remoto
 *                        (equivale al nombre visible del dispositivo, ej. "Pixel de Ana").
 * @property host Dirección IP resuelta del dispositivo dentro de la red local.
 * @property port Puerto TCP en el que el dispositivo remoto escucha conexiones entrantes.
 * @property deviceType Tipo de dispositivo remoto (teléfono, tablet o TV), usado
 *                       únicamente para decidir el ícono representativo en la interfaz.
 *                       Si el dispositivo remoto no anunció su tipo, se asume [DeviceType.PHONE]
 *                       como valor por defecto.
 */
data class NetworkDevice(
    val serviceName: String,
    val host: InetAddress,
    val port: Int,
    val deviceType: DeviceType = DeviceType.PHONE
)