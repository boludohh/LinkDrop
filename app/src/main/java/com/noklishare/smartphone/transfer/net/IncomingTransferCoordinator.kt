package com.noklishare.smartphone.transfer.net

import com.noklishare.smartphone.transfer.model.IncomingTransferRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordina la confirmación por parte del usuario de las transferencias
 * entrantes.
 *
 * El \[FileReceiverManager\] invoca \[awaitUserDecision\] (suspend) al recibir
 * una solicitud y queda en espera hasta que la interfaz resuelva la decisión
 * mediante \[accept\] o \[reject\]. Mientras haya una solicitud pendiente, se
 * expone a través de \[pendingRequest\] para que la UI la muestre.
 *
 * El receptor procesa las conexiones de a una, por lo que nunca existe más de
 * una solicitud pendiente simultánea.
 \*/
class IncomingTransferCoordinator {

    private val _pendingRequest = MutableStateFlow<IncomingTransferRequest?>(null)

    /** Solicitud entrante actualmente pendiente de confirmación, si la hay. \*/
    val pendingRequest: StateFlow<IncomingTransferRequest?> = _pendingRequest.asStateFlow()

    private var currentDecision: CompletableDeferred<Boolean>? = null

    /**
     * Publica la solicitud entrante y suspende hasta que el usuario decida.
     * Devuelve \`true\` si aceptó la transferencia, \`false\` si la rechazó.
     \*/
    suspend fun awaitUserDecision(remoteDeviceName: String, fileName: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        currentDecision = deferred
        _pendingRequest.value = IncomingTransferRequest(remoteDeviceName, fileName)

        return try {
            deferred.await()
        } finally {
            _pendingRequest.value = null
            currentDecision = null
        }
    }

    /** Acepta la transferencia pendiente. \*/
    fun accept() {
        currentDecision?.complete(true)
    }

    /** Rechaza la transferencia pendiente. \*/
    fun reject() {
        currentDecision?.complete(false)
    }
}