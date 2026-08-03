package com.linkdrop.smartphone.network

import com.linkdrop.smartphone.network.model.NetworkDevice
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
 * realmente "dentro" de la aplicación LinkDrop, y exponer únicamente esos.
 *
 * La verificación se basa en que todo dispositivo con LinkDrop abierto mantiene
 * un socket servidor escuchando en el puerto anunciado. La sonda intenta una
 * conexión TCP breve contra ese puerto:
 * - Si la conexión se establece, el dispositivo está activo y se muestra.
 * - Si la conexión es rechazada o expira, se considera que la aplicación
 *   remota ya no está abierta.
 *
 * Para evitar parpadeos ante fallos puntuales de red, un dispositivo solo se
 * retira de la lista disponible tras acumular \[MAX_CONSECUTIVE_FAILURES\]
 * fallos consecutivos de sonda, y reaparece en cuanto una sonda vuelve a ser
 * exitosa.
 *
 * Los dispositivos nuevos se muestran en el momento en que NSD los descubre,
 * por lo que la aparición sigue siendo en tiempo real; la sonda periódica se
 * encarga de retirar a los dispositivos cuya aplicación fue cerrada y cuyo
 * servicio NSD quedó residual en la red.
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

    /** Protege el acceso a \[consecutiveFailures\] y la publicación de la lista disponible. \*/
    private val lock = Any()

    /** Fallos consecutivos de sonda acumulados, indexados por nombre de servicio. \*/
    private val consecutiveFailures = HashMap<String, Int>()

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
        }
        _availableDevices.value = emptyList()
    }

    /**
     * Publica la lista de dispositivos actualmente utilizables: todos los
     * descubiertos por NSD que no hayan acumulado suficientes fallos de sonda
     * consecutivos como para considerarse caídos.
     \*/
    private fun refreshAvailableDevices() {
        synchronized(lock) {
            val discovered = discoveredDevices.value
            _availableDevices.value = discovered.filter { device ->
                val failures = consecutiveFailures[device.serviceName] ?: 0
                failures < MAX_CONSECUTIVE_FAILURES
            }
        }
    }

    /**
     * Ejecuta una ronda completa de sondas sobre los dispositivos descubiertos
     * y actualiza el conteo de fallos consecutivos de cada uno.
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
                if (reachable) {
                    consecutiveFailures.remove(device.serviceName)
                } else {
                    val failures = consecutiveFailures[device.serviceName] ?: 0
                    consecutiveFailures[device.serviceName] = failures + 1
                }
            }

            // Descarta el seguimiento de dispositivos que ya no están descubiertos.
            val discoveredNames = discovered.mapTo(HashSet<String>()) { it.serviceName }
            consecutiveFailures.keys.retainAll(discoveredNames)
        }

        refreshAvailableDevices()
    }

    /**
     * Intenta una conexión TCP breve contra el puerto anunciado por \[device\].
     * Devuelve \`true\` si la conexión se establece, lo que indica que la
     * aplicación LinkDrop remota está abierta y escuchando.
     \*/
    private fun probeDevice(device: NetworkDevice): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(device.host, device.port), CONNECT_TIMEOUT_MS)
            }
        }.isSuccess
    }
}