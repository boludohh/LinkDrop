package com.linkdrop.smartphone.transfer.net

import android.content.Context
import android.util.Log
import com.linkdrop.smartphone.transfer.model.TransferDirection
import com.linkdrop.smartphone.transfer.model.TransferProgress
import com.linkdrop.smartphone.transfer.storage.LinkDropFileStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Encargado exclusivo de recibir archivos enviados por otros dispositivos LinkDrop.
 *
 * Abre un [ServerSocket] en el puerto fijo indicado y queda a la espera de
 * conexiones entrantes. Al recibir una conexión, lee primero un pequeño
 * encabezado con el nombre del dispositivo remoto, el nombre del archivo y su
 * tamaño en bytes, y luego escribe el contenido recibido en la carpeta
 * pública `Download/LinkDrop/` mediante [LinkDropFileStorage].
 *
 * Esta clase no realiza descubrimiento de dispositivos ni envía archivos:
 * esas responsabilidades corresponden a [com.linkdrop.smartphone.network.NsdDiscoveryManager]
 * y al manager del lado cliente respectivamente.
 *
 * @param context Contexto de la aplicación, usado para acceder al almacenamiento de destino.
 * @param listenPort Puerto TCP en el que este dispositivo escuchará conexiones entrantes.
 */
class FileReceiverManager(
    private val context: Context,
    private val listenPort: Int
) {

    companion object {
        private const val TAG = "FileReceiverManager"

        /** Tamaño del búfer de lectura/escritura, en bytes. */
        private const val BUFFER_SIZE = 8192

        /** Tipo MIME genérico usado cuando no se conoce el tipo real del archivo. */
        private const val GENERIC_MIME_TYPE = "application/octet-stream"
    }

    private val fileStorage = LinkDropFileStorage(context)

    private val scope = CoroutineScope(Dispatchers.IO)
    private var listeningJob: Job? = null
    private var serverSocket: ServerSocket? = null

    private val _transferProgress = MutableStateFlow<TransferProgress>(TransferProgress.Idle)

    /** Estado observable de la transferencia de recepción actual. */
    val transferProgress: StateFlow<TransferProgress> = _transferProgress.asStateFlow()

    /**
     * Inicia la escucha de conexiones entrantes en el puerto configurado.
     *
     * Por cada conexión aceptada se procesa una única transferencia de archivo.
     * Al finalizar (con éxito o con error), el servidor vuelve a quedar a la
     * espera de una nueva conexión mientras [stopListening] no sea invocado.
     */
    fun startListening() {
        if (listeningJob != null) {
            Log.w(TAG, "El servidor ya está escuchando, se ignora la nueva solicitud")
            return
        }

        listeningJob = scope.launch {
            runCatching {
                ServerSocket(listenPort).use { socket ->
                    serverSocket = socket
                    Log.i(TAG, "Servidor escuchando en el puerto $listenPort")

                    while (true) {
                        val clientSocket = socket.accept()
                        handleIncomingConnection(clientSocket)
                    }
                }
            }.onFailure { error ->
                Log.e(TAG, "El servidor se detuvo de forma inesperada", error)
            }
        }
    }

    /**
     * Detiene la escucha de conexiones entrantes y libera el puerto utilizado.
     * Debe llamarse cuando la app pasa a segundo plano o se cierra.
     */
    fun stopListening() {
        runCatching {
            serverSocket?.close()
        }.onFailure {
            Log.w(TAG, "Error al cerrar el servidor", it)
        }
        serverSocket = null
        listeningJob?.cancel()
        listeningJob = null
        _transferProgress.value = TransferProgress.Idle
    }

    /**
     * Procesa una conexión entrante completa: lee el encabezado con los
     * metadatos del archivo y luego su contenido, escribiéndolo en el
     * almacenamiento de destino mientras reporta el progreso.
     */
    private fun handleIncomingConnection(clientSocket: Socket) {
        clientSocket.use { socket ->
            runCatching {
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))

                val remoteDeviceName = input.readUTF()
                val fileName = input.readUTF()
                val fileSize = input.readLong()

                _transferProgress.value = TransferProgress.InProgress(
                    fileName = fileName,
                    totalBytes = fileSize,
                    transferredBytes = 0L,
                    direction = TransferDirection.RECEIVING,
                    remoteDeviceName = remoteDeviceName
                )

                val outputStream = fileStorage.createOutputStreamFor(fileName, GENERIC_MIME_TYPE)
                    ?: throw IllegalStateException("No se pudo crear el archivo de destino")

                outputStream.use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var totalRead = 0L

                    while (totalRead < fileSize) {
                        val read = input.read(buffer, 0, buffer.size)
                        if (read == -1) break

                        output.write(buffer, 0, read)
                        totalRead += read

                        _transferProgress.value = TransferProgress.InProgress(
                            fileName = fileName,
                            totalBytes = fileSize,
                            transferredBytes = totalRead,
                            direction = TransferDirection.RECEIVING,
                            remoteDeviceName = remoteDeviceName
                        )
                    }
                }

                _transferProgress.value = TransferProgress.Completed(
                    fileName = fileName,
                    totalBytes = fileSize,
                    direction = TransferDirection.RECEIVING,
                    remoteDeviceName = remoteDeviceName
                )

                Log.i(TAG, "Archivo '$fileName' recibido correctamente ($fileSize bytes)")
            }.onFailure { error ->
                Log.e(TAG, "Error al recibir el archivo", error)
                val currentState = _transferProgress.value
                val fileNameForError = (currentState as? TransferProgress.InProgress)?.fileName ?: "archivo"
                _transferProgress.value = TransferProgress.Failed(
                    fileName = fileNameForError,
                    reason = error.message ?: "Error desconocido durante la recepción"
                )
            }
        }
    }
}