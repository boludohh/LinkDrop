package com.noklishare.smartphone.network

import com.noklishare.smartphone.network.model.NetworkDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Encargado exclusivo de verificar qué dispositivos descubiertos por NSD están
 * realmente "dentro" de la aplicación NokliShare, y exponer únicamente esos.
 *
 * La verificación se basa en que todo dispositivo con NokliShare abierto
 * mantiene un socket servidor escuchando en el puerto anunciado. La sonda
 * intenta una conexión TCP breve contra ese puerto:
 * - Solo cuando una sonda es exitosa el dispositivo se considera verificado y
 *   se muestra en la lista disponible.
 * - Si la conexión es rechazada o expira de forma consecutiva, el dispositivo
 *   deja de considerarse verificado y se retira de la lista.
 *
 * Esto evita mostrar "fantasmas" provenientes de la caché mDNS cuya dirección
 * ya no responde: un dispositivo nunca aparece sin que su puerto esté vivo, y
 * desaparece en cuanto deja de estarlo.
 *
 * @param discoveredDevices Lista observable de dispositivos crudos descubiertos
 * por \[NsdDiscoveryManager\].
 \*/
class DeviceAvailabilityMonitor(
    private val discoveredDevices: StateFlow<List<NetworkDevice>>
) {

    companion object {
        /** Intervalo entre rondas de sondas de disponibilidad, en milisegundos. \*/
        private const val VERIFY_INTERVAL_MS = 2000L

        /** Tiempo máximo de espera al intentar conectar la sonda, en milisegundos. \*/
        private const val CONNECT_TIMEOUT_MS = 1500

        /** Fallos consecutivos de sonda necesarios para retirar un dispositivo. \*/
        private const val MAX_CONSECUTIVE_FAILURES = 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    /** Protege el acceso al estado interno y la publicación de la lista disponible. \*/
    private val lock = Any()

    /** Fallos consecutivos de sonda acumulados, indexados por nombre de servicio. \*/
    private val consecutiveFailures = HashMap<String, Int>()

    /** Servicios cuya sonda fue exitosa al menos una vez y siguen vivos. \*/
    private val verifiedDevices = HashSet<String>()

    private val _availableDevices = MutableStateFlow<List<NetworkDevice>>(emptyList())

    /** Lista observable de dispositivos descubiertos que están realmente activos. \*/
    val availableDevices: StateFlow<List<NetworkDevice>> = _availableDevices.asStateFlow()

    /**
     * Inicia la supervisión: refleja de inmediato los cambios del descubrimiento
     * NSD en la lista disponible y lanza la ronda periódica de sondas.
     \*/
    fun start() {
        if (monitorJob?.isActive == true) {
            return
        }

        monitorJob = scope.launch {
            launch {
                discoveredDevices.collect {
                    refreshAvailableDevices()
                }
            }
            launch {
                while (isActive) {
                    probeDiscoveredDevices()
                    delay(VERIFY_INTERVAL_MS)
                }
            }
        }
    }

    /**
     * Detiene la supervisión y limpia el estado interno.
     \*/
    fun stop() {
        monitorJob?.cancel()
        monitorJob = null

        synchronized(lock) {
            consecutiveFailures.clear()
            verifiedDevices.clear()
        }
        _availableDevices.value = emptyList()
    }

    /**
     * Publica la lista de dispositivos actualmente utilizables: únicamente los
     * descubiertos por NSD que fueron verificados como vivos por una sonda.
     \*/
    private fun refreshAvailableDevices() {
        synchronized(lock) {
            val discovered = discoveredDevices.value
            _availableDevices.value = discovered.filter { device ->
                device.serviceName in verifiedDevices
            }
        }
    }

    /**
     * Ejecuta una ronda completa de sondas sobre los dispositivos descubiertos
     * y actualiza el conjunto de dispositivos verificados.
     \*/
    private fun probeDiscoveredDevices() {
        val discovered = discoveredDevices.value

        // Las sondas se ejecutan antes de tomar el bloqueo porque son la parte lenta.
        val probeResults = ArrayList<Pair<NetworkDevice, Boolean>>(discovered.size)
        for (device in discovered) {
            probeResults.add(device to probeDevice(device))
        }

        synchronized(lock) {
            for ((device, reachable) in probeResults) {
                val name = device.serviceName
                if (reachable) {
                    consecutiveFailures.remove(name)
                    verifiedDevices.add(name)
                } else {
                    val failures = (consecutiveFailures[name] ?: 0) + 1
                    if (failures >= MAX_CONSECUTIVE_FAILURES) {
                        consecutiveFailures.remove(name)
                        verifiedDevices.remove(name)
                    } else {
                        consecutiveFailures[name] = failures
                    }
                }
            }

            // Descarta el seguimiento de dispositivos que ya no están descubiertos.
            val discoveredNames = discovered.mapTo(HashSet<String>()) { it.serviceName }
            consecutiveFailures.keys.retainAll(discoveredNames)
            verifiedDevices.retainAll(discoveredNames)
        }

        refreshAvailableDevices()
    }

    /**
     * Intenta una conexión TCP breve contra el puerto anunciado por \[device\].
     * Devuelve \`true\` si la conexión se establece, lo que indica que la
     * aplicación NokliShare remota está abierta y escuchando.
     \*/
    private fun probeDevice(device: NetworkDevice): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(device.host, device.port), CONNECT_TIMEOUT_MS)
            }
        }.isSuccess
    }
}