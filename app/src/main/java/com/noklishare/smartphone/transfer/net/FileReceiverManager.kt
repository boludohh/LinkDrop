package com.noklishare.smartphone.transfer.net

import android.content.Context
import android.util.Log
import com.noklishare.smartphone.R
import com.noklishare.smartphone.transfer.model.TransferDirection
import com.noklishare.smartphone.transfer.model.TransferLimits
import com.noklishare.smartphone.transfer.model.TransferProgress
import com.noklishare.smartphone.transfer.storage.LinkDropFileStorage
import com.noklishare.smartphone.transfer.util.SpeedTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.ServerSocket
import java.net.Socket

/**
 * Encargado exclusivo de recibir archivos enviados por otros dispositivos NokliShare.
 *
 * Abre un \[ServerSocket\] en el puerto fijo indicado y queda a la espera de
 * conexiones entrantes. Cada conexión se procesa en su propia corrutina para
 * que el servidor nunca quede bloqueado (por ejemplo, mientras una solicitud
 * entrante espera la decisión del usuario); las transferencias de archivo se
 * serializan con un bloqueo para que solo exista una a la vez. Si el servidor
 * se detuviera por un error inesperado, se reinicia automáticamente.
 *
 * Al recibir una conexión, lee primero un pequeño encabezado con el nombre
 * del dispositivo remoto, el nombre del archivo y su tamaño en bytes, y
 * consulta \[onIncomingFileRequest\] para decidir si aceptar o rechazar la
 * transferencia antes de recibir ningún byte del archivo. Los archivos que
 * superan el límite de \[TransferLimits.MAX_FILE_SIZE_BYTES\] se rechazan
 * automáticamente como red de seguridad. Si se acepta, escribe el contenido
 * recibido mediante \[LinkDropFileStorage\] y, al terminar, envía un acuse
 * final al emisor indicando si el archivo se guardó correctamente.
 *
 * Esta clase no realiza descubrimiento de dispositivos ni envía archivos:
 * esas responsabilidades corresponden a \[com.noklishare.smartphone.network.NsdDiscoveryManager\]
 * y al manager del lado cliente respectivamente.
 *
 * @param context Contexto de la aplicación, usado para acceder al almacenamiento de destino.
 * @param listenPort Puerto TCP en el que este dispositivo escuchará conexiones entrantes.
 * @param onIncomingFileRequest Función invocada por cada archivo entrante para decidir
 * si se acepta o se rechaza, recibiendo el nombre del
 * dispositivo remoto y el nombre del archivo propuesto.
 * Devuelve \`true\` para aceptar la transferencia.
 \*/
class FileReceiverManager(
    private val context: Context,
    private val listenPort: Int,
    private val onIncomingFileRequest: suspend (remoteDeviceName: String, fileName: String) -> Boolean = { _, _ -> true }
) {

    companion object {
        private const val TAG = "FileReceiverManager"

        /** Tamaño del búfer de lectura/escritura, en bytes. \*/
        private const val BUFFER_SIZE = 8192

        /** Tipo MIME genérico usado cuando no se conoce el tipo real del archivo. \*/
        private const val GENERIC_MIME_TYPE = "application/octet-stream"

        /** Espera antes de reiniciar el servidor tras un fallo inesperado, en ms. \*/
        private const val RESTART_DELAY_MS = 1000L
    }

    private val fileStorage = LinkDropFileStorage(context)

    private val scope = CoroutineScope(Dispatchers.IO)
    private var listeningJob: Job? = null
    private var serverSocket: ServerSocket? = null

    /** Serializa las transferencias de archivo: solo una a la vez. \*/
    private val transferLock = Mutex()

    private val _transferProgress = MutableStateFlow<TransferProgress>(TransferProgress.Idle)

    /** Estado observable de la transferencia de recepción actual. \*/
    val transferProgress: StateFlow<TransferProgress> = _transferProgress.asStateFlow()

    /**
     * Inicia la escucha de conexiones entrantes en el puerto configurado.
     *
     * Cada conexión aceptada se procesa en su propia corrutina, de modo que el
     * bucle de aceptación nunca se bloquea. Si el servidor se detuviera por un
     * error inesperado, se reintenta su arranque tras una espera breve mientras
     * \[stopListening\] no sea invocado.
     \*/
    fun startListening() {
        if (listeningJob != null) {
            Log.w(TAG, "El servidor ya está escuchando, se ignora la nueva solicitud")
            return
        }

        listeningJob = scope.launch {
            while (isActive) {
                runCatching {
                    ServerSocket(listenPort).use { socket ->
                        serverSocket = socket
                        Log.i(TAG, "Servidor escuchando en el puerto $listenPort")

                        while (isActive) {
                            val clientSocket = socket.accept()
                            launch {
                                runCatching { handleIncomingConnection(clientSocket) }
                                    .onFailure { error ->
                                        Log.e(TAG, "Error al manejar una conexión entrante", error)
                                    }
                            }
                        }
                    }
                }.onFailure { error ->
                    Log.e(TAG, "El servidor se detuvo de forma inesperada; se reiniciará", error)
                }

                serverSocket = null
                if (isActive) {
                    delay(RESTART_DELAY_MS)
                }
            }
        }
    }

    /**
     * Detiene la escucha de conexiones entrantes y libera el puerto utilizado.
     * Debe llamarse cuando la app pasa a segundo plano o se cierra.
     \*/
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
     * Procesa una conexión entrante completa: lee el encabezado con los
     * metadatos del archivo, rechaza automáticamente los archivos que superan
     * el límite de tamaño, intenta reservar el bloqueo de transferencia (si ya
     * hay otra en curso o pendiente, rechaza de inmediato informando al
     * emisor), consulta \[onIncomingFileRequest\] para decidir si aceptar,
     * responde esa decisión al emisor, y en caso afirmativo recibe el
     * contenido del archivo escribiéndolo en el almacenamiento de destino
     * mientras reporta el progreso. Al finalizar, envía el acuse final de
     * guardado (\`true\`/\`false\`) para que el emisor conozca el resultado real.
     *
     * Esta función es \`suspend\` porque \[onIncomingFileRequest\] puede
     * necesitar esperar una interacción del usuario (por ejemplo, tocar un
     * botón de aceptar/rechazar en la interfaz) antes de continuar. Esa espera
     * solo bloquea esta conexión, nunca al servidor completo.
     \*/
    private suspend fun handleIncomingConnection(clientSocket: Socket) {
        clientSocket.use { socket ->
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
                if (error is EOFException) {
                    // Conexión cerrada sin enviar datos: corresponde a las sondas
                    // de disponibilidad de otros dispositivos o a conexiones
                    // abortadas antes del encabezado. No es un error real.
                    Log.d(TAG, "Conexión entrante cerrada sin enviar el encabezado")
                } else {
                    Log.e(TAG, "Error al leer el encabezado de la transferencia entrante", error)
                }
                return
            }

            // Red de seguridad: rechaza archivos por encima del límite permitido,
            // incluso si el emisor fuera una versión antigua o modificada.
            if (fileSize > TransferLimits.MAX_FILE_SIZE_BYTES) {
                Log.w(TAG, "Transferencia de '$fileName' rechazada: supera el límite de tamaño permitido")
                runCatching {
                    output.writeBoolean(false)
                    output.flush()
                }
                _transferProgress.value = TransferProgress.Failed(
                    fileName = fileName,
                    reason = context.getString(R.string.file_exceeds_limit)
                )
                return
            }

            // Solo una transferencia de archivo a la vez: si el bloqueo ya está
            // tomado (otra transferencia en curso o pendiente de decisión), se
            // rechaza informando al emisor.
            if (!transferLock.tryLock()) {
                Log.w(TAG, "Transferencia de '$fileName' rechazada: ya hay una transferencia en curso o pendiente")
                runCatching {
                    output.writeBoolean(false)
                    output.flush()
                }
                return
            }

            try {
                // La consulta de aceptación queda fuera de runCatching porque es
                // una función suspend y runCatching no admite lambdas suspend.
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

                val speedTracker = SpeedTracker()

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

                            val speed = speedTracker.sample(totalRead)
                            _transferProgress.value = TransferProgress.InProgress(
                                fileName = fileName,
                                totalBytes = fileSize,
                                transferredBytes = totalRead,
                                direction = TransferDirection.RECEIVING,
                                remoteDeviceName = remoteDeviceName,
                                speedBytesPerSecond = speed,
                                estimatedRemainingMillis = speedTracker.estimatedRemainingMillis(
                                    transferredBytes = totalRead,
                                    totalBytes = fileSize,
                                    speedBytesPerSecond = speed
                                )
                            )
                        }
                    }

                    // Acuse final: el archivo se guardó correctamente.
                    output.writeBoolean(true)
                    output.flush()

                    _transferProgress.value = TransferProgress.Completed(
                        fileName = fileName,
                        totalBytes = fileSize,
                        direction = TransferDirection.RECEIVING,
                        remoteDeviceName = remoteDeviceName
                    )

                    Log.i(TAG, "Archivo '$fileName' recibido correctamente ($fileSize bytes)")
                }.onFailure { error ->
                    Log.e(TAG, "Error al recibir el archivo", error)

                    // Acuse final de fallo (mejor esfuerzo), para que el emisor
                    // no marque la transferencia como completada.
                    runCatching {
                        output.writeBoolean(false)
                        output.flush()
                    }

                    _transferProgress.value = TransferProgress.Failed(
                        fileName = fileName,
                        reason = error.message ?: "Error desconocido durante la recepción"
                    )
                }
            } finally {
                transferLock.unlock()
            }
        }
    }
}