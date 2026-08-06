package com.noklishare.smartphone.transfer.net

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.noklishare.smartphone.network.model.NetworkDevice
import com.noklishare.smartphone.transfer.model.TransferDirection
import com.noklishare.smartphone.transfer.model.TransferProgress
import com.noklishare.smartphone.transfer.util.SpeedTracker
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
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Encargado exclusivo de enviar un archivo a otro dispositivo NokliShare mediante
 * una conexión Socket TCP directa.
 *
 * Se conecta al \[NetworkDevice\] destino, envía primero un pequeño encabezado
 * con el nombre de este dispositivo, el nombre del archivo y su tamaño en
 * bytes, y espera la confirmación del receptor antes de transmitir el
 * contenido. Tras enviar todos los bytes, espera además un acuse final del
 * receptor que confirma que el archivo se guardó correctamente; si el receptor
 * no pudo guardarlo, la transferencia se marca como fallida. Si el receptor
 * rechaza la transferencia (o no responde), la operación finaliza con
 * \[TransferProgress.Failed\] sin enviar ningún byte del archivo.
 *
 * Esta clase no realiza descubrimiento de dispositivos ni recibe archivos:
 * esas responsabilidades corresponden a \[com.noklishare.smartphone.network.NsdDiscoveryManager\]
 * y a \[FileReceiverManager\] respectivamente.
 *
 * @param context Contexto de la aplicación, usado para leer el archivo a través del \[Uri\] indicado.
 * @param localDeviceName Nombre de este dispositivo, enviado al destino como parte del encabezado.
 \*/
class FileSenderManager(
    private val context: Context,
    private val localDeviceName: String
) {

    companion object {
        private const val TAG = "FileSenderManager"

        /** Tamaño del búfer de lectura/escritura, en bytes. \*/
        private const val BUFFER_SIZE = 8192

        /** Tiempo máximo de espera al intentar establecer la conexión, en milisegundos. \*/
        private const val CONNECT_TIMEOUT_MS = 10_000

        /** Tiempo máximo de espera de la confirmación del receptor, en milisegundos. \*/
        private const val ACCEPT_TIMEOUT_MS = 20_000

        /** Tiempo máximo de espera del acuse final de guardado, en milisegundos. \*/
        private const val FINAL_ACK_TIMEOUT_MS = 20_000
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    private val _transferProgress = MutableStateFlow<TransferProgress>(TransferProgress.Idle)

    /** Estado observable de la transferencia de envío actual. \*/
    val transferProgress: StateFlow<TransferProgress> = _transferProgress.asStateFlow()

    /**
     * Envía el archivo indicado por \[fileUri\] al dispositivo \[targetDevice\].
     * La operación se ejecuta de forma asíncrona; el progreso puede observarse
     * a través de \[transferProgress\].
     *
     * @param fileUri Uri del archivo seleccionado por el usuario, obtenido desde
     * el selector de documentos del sistema.
     * @param targetDevice Dispositivo remoto al que se enviará el archivo.
     \*/
    fun sendFile(fileUri: Uri, targetDevice: NetworkDevice) {
        scope.launch {
            runCatching {
                val (fileName, fileSize) = resolveFileMetadata(fileUri)
                val speedTracker = SpeedTracker()

                _transferProgress.value = TransferProgress.InProgress(
                    fileName = fileName,
                    totalBytes = fileSize,
                    transferredBytes = 0L,
                    direction = TransferDirection.SENDING,
                    remoteDeviceName = targetDevice.serviceName
                )

                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(targetDevice.host, targetDevice.port),
                        CONNECT_TIMEOUT_MS
                    )

                    val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                    output.writeUTF(localDeviceName)
                    output.writeUTF(fileName)
                    output.writeLong(fileSize)
                    output.flush()

                    val input = DataInputStream(BufferedInputStream(socket.getInputStream()))

                    // Espera acotada de la confirmación del receptor: si el destino
                    // quedara colgado sin responder, el envío falla limpiamente en
                    // lugar de quedarse bloqueado para siempre.
                    socket.soTimeout = ACCEPT_TIMEOUT_MS
                    val wasAccepted = input.readBoolean()
                    socket.soTimeout = 0

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

                            val speed = speedTracker.sample(totalSent)
                            _transferProgress.value = TransferProgress.InProgress(
                                fileName = fileName,
                                totalBytes = fileSize,
                                transferredBytes = totalSent,
                                direction = TransferDirection.SENDING,
                                remoteDeviceName = targetDevice.serviceName,
                                speedBytesPerSecond = speed,
                                estimatedRemainingMillis = speedTracker.estimatedRemainingMillis(
                                    transferredBytes = totalSent,
                                    totalBytes = fileSize,
                                    speedBytesPerSecond = speed
                                )
                            )
                        }
                    }
                    output.flush()

                    // Acuse final: el receptor confirma que guardó el archivo.
                    socket.soTimeout = FINAL_ACK_TIMEOUT_MS
                    val wasSaved = input.readBoolean()
                    socket.soTimeout = 0

                    if (!wasSaved) {
                        throw IllegalStateException("El receptor no pudo guardar el archivo")
                    }
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
     * Limpia el estado de una transferencia finalizada (completada o fallida),
     * volviendo a \[TransferProgress.Idle\]. No tiene efecto si hay una
     * transferencia en curso.
     \*/
    fun dismissTransfer() {
        val current = _transferProgress.value
        if (current is TransferProgress.Completed || current is TransferProgress.Failed) {
            _transferProgress.value = TransferProgress.Idle
        }
    }

    /**
     * Resuelve el nombre y el tamaño en bytes del archivo apuntado por \[fileUri\]
     * consultando el \[android.content.ContentResolver\] del sistema.
     \*/
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