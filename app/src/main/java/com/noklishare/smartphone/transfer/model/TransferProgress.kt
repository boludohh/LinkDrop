package com.noklishare.smartphone.transfer.model

/**
 * Representa el estado de una transferencia de archivo, ya sea de envío o de recepción.
 \*/
sealed class TransferProgress {

    /** No hay ninguna transferencia en curso ni finalizada reciente que mostrar. \*/
    data object Idle : TransferProgress()

    /**
     * Una transferencia está actualmente en curso.
     * @property fileName Nombre del archivo que se está transfiriendo.
     * @property totalBytes Tamaño total del archivo en bytes.
     * @property transferredBytes Cantidad de bytes ya transferidos hasta el momento.
     * @property direction Indica si el dispositivo local está enviando o recibiendo el archivo.
     * @property remoteDeviceName Nombre del dispositivo remoto involucrado en la transferencia.
     * @property speedBytesPerSecond Velocidad suavizada de la transferencia, en bytes por segundo.
     * @property estimatedRemainingMillis Tiempo estimado restante en milisegundos, o \`-1\` si aún no es calculable.
     \*/
    data class InProgress(
        val fileName: String,
        val totalBytes: Long,
        val transferredBytes: Long,
        val direction: TransferDirection,
        val remoteDeviceName: String,
        val speedBytesPerSecond: Double = 0.0,
        val estimatedRemainingMillis: Long = -1L
    ) : TransferProgress() {
        /** Porcentaje de avance de la transferencia, entre 0 y 100. \*/
        val percentage: Int
            get() = if (totalBytes <= 0L) {
                0
            } else {
                ((transferredBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
            }
    }

    /**
     * La transferencia finalizó exitosamente.
     * @property fileName Nombre del archivo transferido.
     * @property totalBytes Tamaño total del archivo en bytes.
     * @property direction Indica si el archivo fue enviado o recibido.
     * @property remoteDeviceName Nombre del dispositivo remoto involucrado en la transferencia.
     \*/
    data class Completed(
        val fileName: String,
        val totalBytes: Long,
        val direction: TransferDirection,
        val remoteDeviceName: String
    ) : TransferProgress()

    /**
     * La transferencia falló antes de completarse.
     * @property fileName Nombre del archivo que se intentaba transferir.
     * @property reason Descripción breve del motivo del fallo.
     \*/
    data class Failed(
        val fileName: String,
        val reason: String
    ) : TransferProgress()
}

/** Indica la dirección de una transferencia respecto al dispositivo local. \*/
enum class TransferDirection {
    SENDING,
    RECEIVING
}