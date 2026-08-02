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
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Encargado exclusivo de recibir archivos enviados por otros dispositivos LinkDrop.
 *
 * Abre un [ServerSocket] en el puerto fijo indicado y queda a la espera de
 * conexiones entrantes. Al recibir una conexión, lee primero un pequeño
 * encabezado con el nombre del dispositivo remoto, el nombre del archivo y su
 * tamaño en bytes, y consulta [onIncomingFileRequest] para decidir si aceptar
 * o rechazar la transferencia antes de recibir ningún byte del archivo. Si
 * se acepta, escribe el contenido recibido en la carpeta pública
 * `Download/LinkDrop/` mediante [LinkDropFileStorage].
 *
 * Las conexiones entrantes se procesan de a una por vez; si llega una nueva
 * conexión mientras ya hay una transferencia en curso, se rechaza de inmediato
 * sin consultar [onIncomingFileRequest].
 *
 * Esta clase no realiza descubrimiento de dispositivos ni envía archivos:
 * esas responsabilidades corresponden a [com.linkdrop.smartphone.network.NsdDiscoveryManager]
 * y al manager del lado cliente respectivamente.
 *
 * @param context Contexto de la aplicación, usado para acceder al almacenamiento de destino.
 * @param listenPort Puerto TCP en el que este dispositivo escuchará conexiones entrantes.
 * @param onIncomingFileRequest Función invocada por cada archivo entrante para decidir
 *                               si se acepta o se rechaza, recibiendo el nombre del
 *                               dispositivo remoto y el nombre del archivo propuesto.
 *                               Devuelve `true` para aceptar la transferencia. Por
 *                               defecto acepta siempre, ya que todavía no existe una
 *                               pantalla de confirmación real para el usuario.
 */
class FileReceiverManager(
    private val context: Context,
    private val listenPort: Int,
    private val onIncomingFileRequest: suspend (remoteDeviceName: String, fileName: String) -> Boolean = { _, _ -> true }
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
     * Procesa una conexión entrante completa: primero verifica que no haya
     * otra transferencia en curso, luego lee el encabezado con los metadatos
     * del archivo, consulta [onIncomingFileRequest] para decidir si aceptar,
     * responde esa decisión al emisor, y en caso afirmativo recibe el
     * contenido del archivo escribiéndolo en el almacenamiento de destino
     * mientras reporta el progreso.
     *
     * Esta función es `suspend` porque [onIncomingFileRequest] puede
     * necesitar esperar una interacción del usuario (por ejemplo, tocar un
     * botón de aceptar/rechazar en un diálogo) antes de continuar.
     *
     * Si ya existe una transferencia en curso, la conexión se rechaza
     * inmediatamente sin leer ni procesar nada, para evitar que dos
     * transferencias se ejecuten al mismo tiempo sobre este dispositivo.
     */
    private suspend fun handleIncomingConnection(clientSocket: Socket) {
        clientSocket.use { socket ->
            if (_transferProgress.value is TransferProgress.InProgress) {
                Log.w(TAG, "Conexión entrante rechazada: ya hay una transferencia en curso")
                return
            }

            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

            // La lectura del encabezado se separa en su propio runCatching porque,
            // si falla, no hay ningún archivo identificado todavía sobre el cual
            // reportar un estado Failed con sentido.
            val headerResult = runCatching {
                val remoteDeviceName = input.readUTF()
                val fileName = input.readUTF()
                val fileSize = input.readLong()
                Triple(remoteDeviceName, fileName, fileSize)
            }

            val (remoteDeviceName, fileName, fileSize) = headerResult.getOrElse { error ->
                Log.e(TAG, "Error al leer el encabezado de la transferencia entrante", error)
                return
            }

            // La consulta de aceptación queda fuera de runCatching porque es una
            // función suspend y runCatching no admite lambdas suspend.
            val isAccepted = onIncomingFileRequest(remoteDeviceName, fileName)

            runCatching {
                output.writeBoolean(isAccepted)
                output.flush()
            }.onFailure { error ->
                Log.e(TAG, "Error al responder la confirmación al emisor", error)
                return
            }

            if (!isAccepted) {
                Log.i(TAG, "Transferencia de '$fileName' rechazada")
                return
            }

            runCatching {
                _transferProgress.value = TransferProgress.InProgress(
                    fileName = fileName,
                    totalBytes = fileSize,
                    transferredBytes = 0L,
                    direction = TransferDirection.RECEIVING,
                    remoteDeviceName = remoteDeviceName
                )

                val outputStream = fileStorage.createOutputStreamFor(fileName, GENERIC_MIME_TYPE)
                    ?: throw IllegalStateException("No se pudo crear el archivo de destino")

                outputStream.use { fileOutput ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var totalRead = 0L

                    while (totalRead < fileSize) {
                        val read = input.read(buffer, 0, buffer.size)
                        if (read == -1) break

                        fileOutput.write(buffer, 0, read)
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
                _transferProgress.value = TransferProgress.Failed(
                    fileName = fileName,
                    reason = error.message ?: "Error desconocido durante la recepción"
                )
            }
        }
    }
}