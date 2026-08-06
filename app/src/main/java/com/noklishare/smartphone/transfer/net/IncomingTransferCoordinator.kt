package com.noklishare.smartphone.transfer.net

import com.noklishare.smartphone.transfer.model.IncomingDecision
import com.noklishare.smartphone.transfer.model.IncomingTransferRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Coordina la confirmación por parte del usuario de las transferencias
 * entrantes, con un tiempo límite de \[CONFIRMATION_TIMEOUT_MS\].
 *
 * El \[FileReceiverManager\] invoca \[awaitUserDecision\] (suspend) al recibir
 * una solicitud y queda en espera hasta que la interfaz resuelva la decisión
 * mediante \[accept\] o \[reject\], o hasta que transcurra el tiempo límite,
 * en cuyo caso el resultado es \[IncomingDecision.TIMEOUT\].
 *
 * Mientras hay una solicitud pendiente, se expone a través de
 * \[pendingRequest\] para que la UI la muestre, y \[remainingSeconds\]
 * publica la cuenta regresiva (15 → 0) para pintarla en la tarjeta de
 * confirmación.
 *
 * El receptor procesa las conexiones de a una, por lo que nunca existe más de
 * una solicitud pendiente simultánea.
 \*/
class IncomingTransferCoordinator {

    companion object {
        /** Tiempo límite para confirmar una transferencia entrante, en milisegundos. \*/
        const val CONFIRMATION_TIMEOUT_MS = 15_000L

        /** Valor inicial de la cuenta regresiva, en segundos. \*/
        private const val INITIAL_COUNTDOWN_SECONDS = (CONFIRMATION_TIMEOUT_MS / 1000).toInt()
    }

    private val _pendingRequest = MutableStateFlow<IncomingTransferRequest?>(null)

    /** Solicitud entrante actualmente pendiente de confirmación, si la hay. \*/
    val pendingRequest: StateFlow<IncomingTransferRequest?> = _pendingRequest.asStateFlow()

    private val _remainingSeconds = MutableStateFlow(0)

    /** Segundos restantes para que expire la confirmación; solo tiene sentido con una solicitud pendiente. \*/
    val remainingSeconds: StateFlow<Int> = _remainingSeconds.asStateFlow()

    private var currentDecision: CompletableDeferred<IncomingDecision>? = null

    /**
     * Publica la solicitud entrante, inicia la cuenta regresiva y suspende
     * hasta que el usuario decida (\[accept\]/\[reject\]) o el tiempo se
     * agote. Devuelve la decisión resultante, incluyendo
     * \[IncomingDecision.TIMEOUT\] si nadie decidió a tiempo.
     \*/
    suspend fun awaitUserDecision(remoteDeviceName: String, fileName: String): IncomingDecision {
        val deferred = CompletableDeferred<IncomingDecision>()
        currentDecision = deferred
        _pendingRequest.value = IncomingTransferRequest(remoteDeviceName, fileName)
        _remainingSeconds.value = INITIAL_COUNTDOWN_SECONDS

        return try {
            coroutineScope {
                val ticker = launch {
                    var seconds = INITIAL_COUNTDOWN_SECONDS
                    while (seconds > 0 && isActive) {
                        delay(1000L)
                        seconds--
                        _remainingSeconds.value = seconds
                    }
                }

                val result = withTimeoutOrNull(CONFIRMATION_TIMEOUT_MS) {
                    deferred.await()
                }
                ticker.cancel()
                result ?: IncomingDecision.TIMEOUT
            }
        } finally {
            _pendingRequest.value = null
            currentDecision = null
        }
    }

    /** Acepta la transferencia pendiente. \*/
    fun accept() {
        currentDecision?.complete(IncomingDecision.ACCEPT)
    }

    /** Rechaza la transferencia pendiente. \*/
    fun reject() {
        currentDecision?.complete(IncomingDecision.REJECT)
    }
}