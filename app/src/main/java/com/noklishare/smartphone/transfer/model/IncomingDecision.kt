package com.noklishare.smartphone.transfer.model

/**
 * Resultado de la confirmación de una transferencia entrante en el
 * dispositivo receptor.
 \*/
enum class IncomingDecision {
    /** El usuario aceptó la transferencia. \*/
    ACCEPT,

    /** El usuario rechazó la transferencia. \*/
    REJECT,

    /** El tiempo de confirmación se agotó sin una decisión del usuario. \*/
    TIMEOUT
}