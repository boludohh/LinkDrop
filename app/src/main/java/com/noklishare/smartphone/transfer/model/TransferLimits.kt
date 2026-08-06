package com.noklishare.smartphone.transfer.model

/**
 * Límites aplicables a las transferencias de NokliShare, compartidos por el
 * emisor y el receptor para mantener una única fuente de verdad.
 \*/
object TransferLimits {

    /** Tamaño máximo permitido por archivo (10 GB), en bytes. \*/
    const val MAX_FILE_SIZE_BYTES: Long = 10L * 1024L * 1024L * 1024L
}