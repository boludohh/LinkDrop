package com.noklishare.smartphone.transfer.model

/**
 * Solicitud de transferencia entrante pendiente de confirmación del usuario.
 *
 * @property remoteDeviceName Nombre del dispositivo remoto que quiere enviar el archivo.
 * @property fileName Nombre del archivo propuesto por el emisor.
 \*/
data class IncomingTransferRequest(
    val remoteDeviceName: String,
    val fileName: String
)