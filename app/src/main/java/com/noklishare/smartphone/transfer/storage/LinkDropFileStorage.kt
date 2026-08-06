package com.noklishare.smartphone.transfer.storage

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Encargado exclusivo de resolver la ubicación de destino y crear el archivo
 * de salida para los archivos recibidos por NokliShare.
 *
 * Todos los archivos recibidos se guardan en una carpeta pública fija llamada
 * "NokliShare" dentro del directorio de Descargas del dispositivo
 * (\`Download/NokliShare/\`). Esta ubicación no es configurable por el usuario.
 *
 * En Android 10+ (API 29) utiliza \[MediaStore\] para cumplir con Scoped
 * Storage sin permisos adicionales. En Android 8/9 (API 28 y anteriores) usa
 * la API de archivos clásica sobre la carpeta pública de Descargas, lo cual
 * requiere el permiso \[android.Manifest.permission.WRITE_EXTERNAL_STORAGE\]
 * (declarado con \`maxSdkVersion=28\` y solicitado en tiempo de ejecución).
 *
 * Esta clase no maneja sockets ni lógica de red: su única responsabilidad es
 * la escritura del archivo en el almacenamiento del dispositivo.
 \*/
class LinkDropFileStorage(private val context: Context) {

    companion object {
        private const val TAG = "LinkDropFileStorage"

        /** Subcarpeta fija dentro de Descargas donde se guardan todos los archivos recibidos. \*/
        private const val NOKLISHARE_SUBFOLDER = "NokliShare"
    }

    /**
     * Crea una nueva entrada en el almacenamiento público del dispositivo para
     * el archivo indicado y devuelve un \[OutputStream\] listo para escribir
     * su contenido.
     *
     * @param fileName Nombre del archivo a crear, incluyendo su extensión.
     * @param mimeType Tipo MIME del archivo. Si no se conoce, puede usarse
     * "application/octet-stream" como valor genérico.
     * @return Un \[OutputStream\] abierto sobre el destino final del archivo,
     * o \`null\` si no fue posible crear la entrada en el almacenamiento.
     \*/
    fun createOutputStreamFor(fileName: String, mimeType: String): OutputStream? {
        return if (Build.VERSION.SDK_INT >= 29) {
            createMediaStoreOutputStream(fileName, mimeType)
        } else {
            createLegacyOutputStream(fileName)
        }
    }

    /**
     * Ruta moderna (Android 10+): inserta una entrada en \[MediaStore.Downloads\]
     * con la ruta relativa \`Download/NokliShare/\` y abre su stream de salida.
     \*/
    private fun createMediaStoreOutputStream(fileName: String, mimeType: String): OutputStream? {
        return runCatching {
            val resolver = context.applicationContext.contentResolver

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + NOKLISHARE_SUBFOLDER
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

    /**
     * Ruta clásica (Android 8/9): crea el archivo directamente en la carpeta
     * pública \`Download/NokliShare/\`. Si ya existe un archivo con el mismo
     * nombre, genera un nombre único agregando un sufijo "(n)".
     \*/
    @Suppress("DEPRECATION")
    private fun createLegacyOutputStream(fileName: String): OutputStream? {
        return runCatching {
            val downloadsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(downloadsDir, NOKLISHARE_SUBFOLDER)

            if (!targetDir.exists() && !targetDir.mkdirs()) {
                Log.e(TAG, "No se pudo crear la carpeta de destino: ${targetDir.absolutePath}")
                return@runCatching null
            }

            val targetFile = resolveUniqueFile(File(targetDir, fileName))
            FileOutputStream(targetFile)
        }.getOrElse {
            Log.e(TAG, "No se pudo crear el archivo de destino para '$fileName'", it)
            null
        }
    }

    /**
     * Devuelve \[original\] si no existe; si existe, busca un nombre libre con
     * el patrón "nombre (n).ext" para no sobrescribir archivos previos.
     \*/
    private fun resolveUniqueFile(original: File): File {
        if (!original.exists()) {
            return original
        }

        val baseName = original.nameWithoutExtension
        val extension = original.extension
        var index = 1

        while (true) {
            val candidateName = if (extension.isEmpty()) {
                "$baseName ($index)"
            } else {
                "$baseName ($index).$extension"
            }
            val candidate = File(original.parentFile, candidateName)
            if (!candidate.exists()) {
                return candidate
            }
            index++
        }
    }
}