package com.noklishare.smartphone.transfer.net

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.noklishare.smartphone.network.model.NetworkDevice
import com.noklishare.smartphone.transfer.model.TransferDirection
import com.noklishare.smartphone.transfer.model.TransferProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

/**
 * Encargado exclusivo de enviar un archivo a otro dispositivo LinkDrop mediante
 * una conexión Socket TCP directa.
 *
 * Se conecta al [NetworkDevice] destino, envía primero un pequeño encabezado
 * con el nombre de este dispositivo, el nombre del archivo y su tamaño en
 * bytes, y espera la confirmación del receptor antes de transmitir el
 * contenido. Si el receptor rechaza la transferencia (o no responde), la
 * operación finaliza con [TransferProgress.Failed] sin enviar ningún byte
 * del archivo.
 *
 * Esta clase no realiza descubrimiento de dispositivos ni recibe archivos:
 * esas responsabilidades corresponden a [com.linkdrop.smartphone.network.NsdDiscoveryManager]
 * y a [FileReceiverManager] respectivamente.
 *
 * @param context Contexto de la aplicación, usado para leer el archivo a través del [Uri] indicado.
 * @param localDeviceName Nombre de este dispositivo, enviado al destino como parte del encabezado.
 */
class FileSenderManager(
    private val context: Context,
    private val localDeviceName: String
) {

    companion object {
        private const val TAG = "FileSenderManager"

        /** Tamaño del búfer de lectura/escritura, en bytes. */
        private const val BUFFER_SIZE = 8192

        /** Tiempo máximo de espera al intentar establecer la conexión, en milisegundos. */
        private const val CONNECT_TIMEOUT_MS = 10_000
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    private val _transferProgress = MutableStateFlow<TransferProgress>(TransferProgress.Idle)

    /** Estado observable de la transferencia de envío actual. */
    val transferProgress: StateFlow<TransferProgress> = _transferProgress.asStateFlow()

    /**
     * Envía el archivo indicado por [fileUri] al dispositivo [targetDevice].
     *
     * La operación se ejecuta de forma asíncrona; el progreso puede observarse
     * a través de [transferProgress].
     *
     * @param fileUri Uri del archivo seleccionado por el usuario, obtenido desde
     *                el selector de documentos del sistema.
     * @param targetDevice Dispositivo remoto al que se enviará el archivo.
     */
    fun sendFile(fileUri: Uri, targetDevice: NetworkDevice) {
        scope.launch {
            runCatching {
                val (fileName, fileSize) = resolveFileMetadata(fileUri)

                _transferProgress.value = TransferProgress.InProgress(
                    fileName = fileName,
                    totalBytes = fileSize,
                    transferredBytes = 0L,
                    direction = TransferDirection.SENDING,
                    remoteDeviceName = targetDevice.serviceName
                )

                Socket().use { socket ->
                    socket.connect(
                        java.net.InetSocketAddress(targetDevice.host, targetDevice.port),
                        CONNECT_TIMEOUT_MS
                    )

                    val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                    output.writeUTF(localDeviceName)
                    output.writeUTF(fileName)
                    output.writeLong(fileSize)
                    output.flush()

                    val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                    val wasAccepted = input.readBoolean()

                    if (!wasAccepted) {
                        throw IllegalStateException("El destinatario rechazó la transferencia")
                    }

                    val fileInputStream = context.contentResolver.openInputStream(fileUri)
                        ?: throw IllegalStateException("No se pudo abrir el archivo seleccionado")

                    fileInputStream.use { fileInput ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var totalSent = 0L
                        var read: Int

                        while (fileInput.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            totalSent += read

                            _transferProgress.value = TransferProgress.InProgress(
                                fileName = fileName,
                                totalBytes = fileSize,
                                transferredBytes = totalSent,
                                direction = TransferDirection.SENDING,
                                remoteDeviceName = targetDevice.serviceName
                            )
                        }
                    }

                    output.flush()
                }

                _transferProgress.value = TransferProgress.Completed(
                    fileName = fileName,
                    totalBytes = fileSize,
                    direction = TransferDirection.SENDING,
                    remoteDeviceName = targetDevice.serviceName
                )

                Log.i(TAG, "Archivo '$fileName' enviado correctamente ($fileSize bytes)")
            }.onFailure { error ->
                Log.e(TAG, "Error al enviar el archivo", error)
                val currentState = _transferProgress.value
                val fileNameForError = (currentState as? TransferProgress.InProgress)?.fileName ?: "archivo"
                _transferProgress.value = TransferProgress.Failed(
                    fileName = fileNameForError,
                    reason = error.message ?: "Error desconocido durante el envío"
                )
            }
        }
    }

    /**
     * Resuelve el nombre y el tamaño en bytes del archivo apuntado por [fileUri]
     * consultando el [android.content.ContentResolver] del sistema.
     */
    private fun resolveFileMetadata(fileUri: Uri): Pair<String, Long> {
        var fileName = "archivo"
        var fileSize = 0L

        context.contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)

            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex) ?: fileName
                }
                if (sizeIndex >= 0) {
                    fileSize = cursor.getLong(sizeIndex)
                }
            }
        }

        return fileName to fileSize
    }
}