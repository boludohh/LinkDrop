package com.noklishare.smartphone.transfer.util

/**
 * Calcula la velocidad aproximada y el tiempo estimado restante de una
 * transferencia a partir de muestras acumuladas de bytes y tiempo.
 *
 * Cada instancia debe usarse desde un único bucle de transferencia;
 * no es segura para llamadas concurrentes.
 \*/
class SpeedTracker {

    private var startNanos: Long = -1L
    private var smoothedSpeed: Double = 0.0

    /**
     * Registra una muestra con el total de bytes transferidos hasta el momento
     * y devuelve la velocidad suavizada en bytes por segundo.
     \*/
    fun sample(transferredBytes: Long): Double {
        val now = System.nanoTime()
        if (startNanos == -1L) {
            startNanos = now
            return 0.0
        }

        val elapsedSeconds = (now - startNanos) / 1_000_000_000.0
        if (elapsedSeconds <= 0.0) {
            return smoothedSpeed
        }

        val overallSpeed = transferredBytes.toDouble() / elapsedSeconds
        smoothedSpeed = if (smoothedSpeed == 0.0) {
            overallSpeed
        } else {
            (smoothedSpeed * 0.7) + (overallSpeed * 0.3)
        }
        return smoothedSpeed
    }

    /**
     * Estima los milisegundos restantes para completar la transferencia con la
     * velocidad indicada. Devuelve \`-1\` si la velocidad aún no es utilizable.
     \*/
    fun estimatedRemainingMillis(
        transferredBytes: Long,
        totalBytes: Long,
        speedBytesPerSecond: Double
    ): Long {
        if (speedBytesPerSecond <= 0.0) {
            return -1L
        }
        val remainingBytes = (totalBytes - transferredBytes).coerceAtLeast(0L)
        return ((remainingBytes.toDouble() / speedBytesPerSecond) * 1000.0).toLong()
    }
}