package com.noklishare.smartphone.transfer.storage

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.OutputStream

/**
 * Encargado exclusivo de resolver la ubicación de destino y crear el archivo
 * de salida para los archivos recibidos por LinkDrop.
 *
 * Todos los archivos recibidos se guardan en una carpeta pública fija llamada
 * "LinkDrop" dentro del directorio de Descargas del dispositivo
 * (`Download/LinkDrop/`). Esta ubicación no es configurable por el usuario.
 *
 * Utiliza [MediaStore] para cumplir con las restricciones de Scoped Storage
 * introducidas en Android 10, evitando así requerir permisos de almacenamiento
 * más amplios de lo necesario.
 *
 * Esta clase no maneja sockets ni lógica de red: su única responsabilidad es
 * la escritura del archivo en el almacenamiento del dispositivo.
 */
class LinkDropFileStorage(private val context: Context) {

    companion object {
        private const val TAG = "LinkDropFileStorage"

        /** Subcarpeta fija dentro de Descargas donde se guardan todos los archivos recibidos. */
        private const val LINKDROP_SUBFOLDER = "LinkDrop"
    }

    /**
     * Crea una nueva entrada en el almacenamiento público del dispositivo para
     * el archivo indicado y devuelve un [OutputStream] listo para escribir su contenido.
     *
     * @param fileName Nombre del archivo a crear, incluyendo su extensión.
     * @param mimeType Tipo MIME del archivo. Si no se conoce, puede usarse
     *                 "application/octet-stream" como valor genérico.
     * @return Un [OutputStream] abierto sobre el destino final del archivo,
     *         o `null` si no fue posible crear la entrada en el almacenamiento.
     */
    fun createOutputStreamFor(fileName: String, mimeType: String): OutputStream? {
        return runCatching {
            val resolver = context.applicationContext.contentResolver

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + LINKDROP_SUBFOLDER
                )
            }

            val collectionUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val itemUri = resolver.insert(collectionUri, contentValues)
                ?: return@runCatching null

            resolver.openOutputStream(itemUri)
        }.getOrElse {
            Log.e(TAG, "No se pudo crear el archivo de destino para '$fileName'", it)
            null
        }
    }
}