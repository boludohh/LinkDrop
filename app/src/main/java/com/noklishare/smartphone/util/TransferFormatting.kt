package com.noklishare.smartphone.util

import android.content.Context
import android.text.format.Formatter

/**
 * Utilidades de formato para los datos visibles de una transferencia
 * (tamaños, velocidad y tiempos), usando las unidades localizadas del sistema.
 \*/
object TransferFormatting {

    /** Formatea una cantidad de bytes como tamaño legible (ej. "2,4 MB"). \*/
    fun bytes(context: Context, bytes: Long): String {
        return Formatter.formatFileSize(context, bytes)
    }

    /** Formatea una velocidad en bytes/segundo como tamaño legible por segundo. \*/
    fun speed(context: Context, bytesPerSecond: Double): String {
        return Formatter.formatFileSize(context, bytesPerSecond.toLong()) + "/s"
    }

    /** Formatea una duración en milisegundos como tiempo legible (ej. "45 s", "1 min 05 s"). \*/
    fun duration(millis: Long): String {
        val totalSeconds = (millis + 999) / 1000
        return if (totalSeconds < 60) {
            "$totalSeconds s"
        } else {
            "${totalSeconds / 60} min ${totalSeconds % 60} s"
        }
    }
}