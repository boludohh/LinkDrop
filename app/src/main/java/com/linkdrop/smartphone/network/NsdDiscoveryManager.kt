package com.linkdrop.smartphone.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.util.Log
import com.linkdrop.smartphone.network.model.NetworkDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

/**
 * Encargado exclusivo del descubrimiento de dispositivos LinkDrop en la red local
 * mediante Network Service Discovery (NSD).
 *
 * Responsabilidades:
 * - Publicar (registrar) este dispositivo como un servicio LinkDrop visible para otros.
 * - Descubrir otros dispositivos LinkDrop presentes en la misma red Wi-Fi.
 * - Exponer la lista de dispositivos encontrados como [StateFlow] para que la UI
 *   la observe de forma reactiva.
 *
 * Esta clase no realiza transferencias de archivos ni maneja sockets: esa
 * responsabilidad corresponde a los managers de la Fase 2 (ServerSocket / Socket).
 *
 * @param context Contexto de la aplicación, usado para obtener el [NsdManager] del
 *                sistema y la dirección IP local del dispositivo.
 * @param localDeviceName Nombre que se anunciará en la red para identificar este dispositivo.
 * @param localServicePort Puerto TCP en el que este dispositivo escuchará conexiones entrantes.
 */
class NsdDiscoveryManager(
    private val context: Context,
    private val localDeviceName: String,
    private val localServicePort: Int
) {

    companion object {
        private const val TAG = "NsdDiscoveryManager"

        /** Tipo de servicio NSD reservado para la identificación de dispositivos LinkDrop. */
        const val SERVICE_TYPE = "_linkdrop._tcp."
    }

    private val nsdManager: NsdManager by lazy {
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** Nombre real bajo el cual quedó registrado el servicio (puede variar si hubo colisión de nombres). */
    private var registeredServiceName: String? = null

    private val _discoveredDevices = MutableStateFlow<List<NetworkDevice>>(emptyList())

    /** Lista observable de dispositivos LinkDrop actualmente visibles en la red. */
    val discoveredDevices: StateFlow<List<NetworkDevice>> = _discoveredDevices.asStateFlow()

    /**
     * Publica este dispositivo en la red local para que otros dispositivos LinkDrop
     * puedan encontrarlo mediante descubrimiento NSD.
     *
     * Es seguro llamar a este método una sola vez por sesión de uso; para volver a
     * registrar el servicio primero debe llamarse a [stopPublishing].
     */
    fun startPublishing() {
        if (registrationListener != null) {
            Log.w(TAG, "El servicio ya está siendo publicado, se ignora la nueva solicitud")
            return
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = localDeviceName
            serviceType = SERVICE_TYPE
            port = localServicePort
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                registeredServiceName = serviceInfo.serviceName
                Log.i(TAG, "Servicio publicado correctamente como: ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Fallo al publicar el servicio. Código de error: $errorCode")
                registrationListener = null
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Servicio despublicado: ${serviceInfo.serviceName}")
                registeredServiceName = null
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Fallo al despublicar el servicio. Código de error: $errorCode")
            }
        }

        registrationListener = listener
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    /**
     * Detiene la publicación de este dispositivo en la red. Debe llamarse cuando
     * la app pasa a segundo plano o se cierra, para no permanecer visible innecesariamente.
     */
    fun stopPublishing() {
        val listener = registrationListener ?: return
        runCatching {
            nsdManager.unregisterService(listener)
        }.onFailure {
            Log.w(TAG, "Error al intentar despublicar el servicio", it)
        }
        registrationListener = null
    }

    /**
     * Inicia la búsqueda activa de otros dispositivos LinkDrop en la red local.
     *
     * Los dispositivos encontrados se resuelven automáticamente (host y puerto)
     * y se agregan a [discoveredDevices]. El propio dispositivo se descarta comparando
     * su dirección IP local, no su nombre, ya que el nombre registrado puede no
     * confirmarse todavía en el momento en que se recibe el primer resultado.
     * Es seguro llamar a este método una sola vez por sesión de uso; para reiniciar
     * la búsqueda primero debe llamarse a [stopDiscovery].
     */
    fun startDiscovery() {
        if (discoveryListener != null) {
            Log.w(TAG, "El descubrimiento ya está activo, se ignora la nueva solicitud")
            return
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "Descubrimiento iniciado para el tipo de servicio: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                resolveService(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Dispositivo perdido: ${serviceInfo.serviceName}")
                removeDevice(serviceInfo.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Descubrimiento detenido para el tipo de servicio: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Fallo al iniciar el descubrimiento. Código de error: $errorCode")
                discoveryListener = null
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Fallo al detener el descubrimiento. Código de error: $errorCode")
            }
        }

        discoveryListener = listener
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    /**
     * Detiene la búsqueda de dispositivos y limpia la lista de dispositivos encontrados.
     * Debe llamarse cuando la pantalla que muestra la lista deja de estar visible.
     */
    fun stopDiscovery() {
        val listener = discoveryListener ?: return
        runCatching {
            nsdManager.stopServiceDiscovery(listener)
        }.onFailure {
            Log.w(TAG, "Error al intentar detener el descubrimiento", it)
        }
        discoveryListener = null
        _discoveredDevices.value = emptyList()
    }

    /**
     * Resuelve la información de conexión (host y puerto) de un servicio encontrado
     * durante el descubrimiento. Si la dirección resuelta coincide con la IP local
     * de este dispositivo, el resultado se descarta por tratarse del propio servicio
     * publicado. En caso contrario, se agrega a la lista observable.
     */
    private fun resolveService(serviceInfo: NsdServiceInfo) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(
                    TAG,
                    "Fallo al resolver el servicio '${serviceInfo.serviceName}'. Código de error: $errorCode"
                )
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val resolvedHost: InetAddress = serviceInfo.host ?: return

                if (isLocalDeviceAddress(resolvedHost)) {
                    Log.i(TAG, "Se descarta el propio servicio publicado (IP local: ${resolvedHost.hostAddress})")
                    return
                }

                val device = NetworkDevice(
                    serviceName = serviceInfo.serviceName,
                    host = resolvedHost,
                    port = serviceInfo.port
                )
                addOrUpdateDevice(device)
            }
        })
    }

    /**
     * Determina si la dirección [candidateHost] corresponde a la propia IP local
     * del dispositivo dentro de la red Wi-Fi actual.
     */
    private fun isLocalDeviceAddress(candidateHost: InetAddress): Boolean {
        val localIpAddress = getLocalIpAddress() ?: return false
        return candidateHost.hostAddress == localIpAddress
    }

    /**
     * Obtiene la dirección IP local del dispositivo dentro de la red Wi-Fi actual,
     * utilizando el [WifiManager] del sistema.
     */
    private fun getLocalIpAddress(): String? {
        return runCatching {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipInt = wifiManager.connectionInfo?.ipAddress ?: return null
            if (ipInt == 0) return null
            Formatter.formatIpAddress(ipInt)
        }.getOrElse {
            Log.w(TAG, "No se pudo obtener la IP local del dispositivo", it)
            null
        }
    }

    private fun addOrUpdateDevice(device: NetworkDevice) {
        val currentList = _discoveredDevices.value
        val existingIndex = currentList.indexOfFirst { it.serviceName == device.serviceName }

        _discoveredDevices.value = if (existingIndex >= 0) {
            currentList.toMutableList().apply { set(existingIndex, device) }
        } else {
            currentList + device
        }
    }

    private fun removeDevice(serviceName: String) {
        _discoveredDevices.value = _discoveredDevices.value.filterNot { it.serviceName == serviceName }
    }
}