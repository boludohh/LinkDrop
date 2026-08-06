package com.noklishare.smartphone.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.util.Log
import com.noklishare.smartphone.network.model.DeviceType
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
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Encargado exclusivo del descubrimiento de dispositivos NokliShare en la red
 * local mediante Network Service Discovery (NSD).
 *
 * Responsabilidades:
 * - Publicar (registrar) este dispositivo como un servicio NokliShare visible
 *   para otros, anunciando además su tipo de dispositivo como metadata.
 * - Descubrir otros dispositivos NokliShare presentes en la misma red Wi-Fi.
 * - Re-resolver periódicamente los servicios descubiertos para mantener
 *   vigentes la dirección IP y el puerto anunciados, evitando registros
 *   obsoletos de la caché mDNS.
 * - Mantener adquirido un \[WifiManager.MulticastLock\] mientras el servicio
 *   está activo, para que el sistema no filtre el tráfico mDNS y el
 *   descubrimiento sea fiable.
 * - Exponer la lista de dispositivos encontrados como \[StateFlow\] para que
 *   la UI la observe de forma reactiva.
 *
 * Esta clase no realiza transferencias de archivos ni maneja sockets: esa
 * responsabilidad corresponde a los managers de transferencia (ServerSocket / Socket).
 *
 * @param context Contexto de la aplicación, usado para obtener el \[NsdManager\] del
 * sistema y la dirección IP local del dispositivo.
 * @param localDeviceName Nombre que se anunciará en la red para identificar este dispositivo.
 * @param localServicePort Puerto TCP en el que este dispositivo escuchará conexiones entrantes.
 * @param localDeviceType Tipo de este dispositivo, anunciado en la red para que
 * los remotos elijan el icono representativo correcto.
 \*/
class NsdDiscoveryManager(
    private val context: Context,
    private val localDeviceName: String,
    private val localServicePort: Int,
    private val localDeviceType: DeviceType
) {

    companion object {
        private const val TAG = "NsdDiscoveryManager"

        /** Tipo de servicio NSD reservado para la identificación de dispositivos NokliShare. \*/
        const val SERVICE_TYPE = "_noklishare._tcp."

        /** Clave del atributo NSD (registro TXT) que transporta el tipo de dispositivo. \*/
        private const val DEVICE_TYPE_ATTRIBUTE = "device_type"

        /** Nombre identificativo del MulticastLock de la aplicación. \*/
        private const val MULTICAST_LOCK_TAG = "noklishare_nsd_multicast"

        /** Intervalo entre re-resoluciones periódicas de servicios, en ms. \*/
        private const val REFRESH_INTERVAL_MS = 5000L
    }

    private val nsdManager: NsdManager by lazy {
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var refreshJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /** Servicios descubiertos actualmente, para poder re-resolverlos periódicamente. \*/
    private val foundServices = ConcurrentHashMap<String, NsdServiceInfo>()

    /** Nombre real bajo el cual quedó registrado el servicio (puede variar si hubo colisión de nombres). \*/
    private var registeredServiceName: String? = null

    private val _discoveredDevices = MutableStateFlow<List<NetworkDevice>>(emptyList())

    /** Lista observable de dispositivos NokliShare actualmente visibles en la red. \*/
    val discoveredDevices: StateFlow<List<NetworkDevice>> = _discoveredDevices.asStateFlow()

    /**
     * Publica este dispositivo en la red local para que otros dispositivos
     * NokliShare puedan encontrarlo mediante descubrimiento NSD.
     *
     * Es seguro llamar a este método una sola vez por sesión de uso; para
     * volver a registrar el servicio primero debe llamarse a \[stopPublishing\].
     \*/
    fun startPublishing() {
        if (registrationListener != null) {
            Log.w(TAG, "El servicio ya está siendo publicado, se ignora la nueva solicitud")
            return
        }

        acquireMulticastLock()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = localDeviceName
            serviceType = SERVICE_TYPE
            port = localServicePort
            setAttribute(DEVICE_TYPE_ATTRIBUTE, localDeviceType.name)
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
     \*/
    fun stopPublishing() {
        val listener = registrationListener ?: return
        runCatching {
            nsdManager.unregisterService(listener)
        }.onFailure {
            Log.w(TAG, "Error al intentar despublicar el servicio", it)
        }
        registrationListener = null
        releaseMulticastLock()
    }

    /**
     * Inicia la búsqueda activa de otros dispositivos NokliShare en la red local.
     *
     * Los dispositivos encontrados se resuelven automáticamente (host, puerto y
     * tipo) y se agregan a \[discoveredDevices\]. El propio dispositivo se
     * descarta comparando su dirección IP local, no su nombre, ya que el nombre
     * registrado puede no confirmarse todavía en el momento en que se recibe el
     * primer resultado.
     *
     * Además, mientras el descubrimiento esté activo, los servicios conocidos se
     * re-resuelven periódicamente para refrescar su dirección IP y su puerto,
     * evitando conectar contra registros obsoletos de la caché mDNS.
     *
     * Es seguro llamar a este método una sola vez por sesión de uso; para
     * reiniciar la búsqueda primero debe llamarse a \[stopDiscovery\].
     \*/
    fun startDiscovery() {
        if (discoveryListener != null) {
            Log.w(TAG, "El descubrimiento ya está activo, se ignora la nueva solicitud")
            return
        }

        acquireMulticastLock()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "Descubrimiento iniciado para el tipo de servicio: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                foundServices[serviceInfo.serviceName] = serviceInfo
                resolveService(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Dispositivo perdido: ${serviceInfo.serviceName}")
                foundServices.remove(serviceInfo.serviceName)
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

        refreshJob = scope.launch {
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                for (serviceInfo in foundServices.values) {
                    resolveService(serviceInfo)
                }
            }
        }
    }

    /**
     * Detiene la búsqueda de dispositivos y limpia la lista de dispositivos encontrados.
     * Debe llamarse cuando la pantalla que muestra la lista deja de estar visible.
     \*/
    fun stopDiscovery() {
        val listener = discoveryListener ?: return
        runCatching {
            nsdManager.stopServiceDiscovery(listener)
        }.onFailure {
            Log.w(TAG, "Error al intentar detener el descubrimiento", it)
        }
        discoveryListener = null
        refreshJob?.cancel()
        refreshJob = null
        foundServices.clear()
        _discoveredDevices.value = emptyList()
        releaseMulticastLock()
    }

    /**
     * Adquiere el MulticastLock de Wi-Fi para que el sistema no filtre el
     * tráfico mDNS mientras el servicio NSD está activo. Sin este bloqueo, el
     * descubrimiento puede volverse intermitente cuando la radio entra en
     * ahorro de energía.
     \*/
    private fun acquireMulticastLock() {
        runCatching {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = multicastLock ?: wifiManager
                .createMulticastLock(MULTICAST_LOCK_TAG)
                .apply { setReferenceCounted(false) }
                .also { multicastLock = it }
            if (!lock.isHeld) {
                lock.acquire()
                Log.i(TAG, "MulticastLock de Wi-Fi adquirido")
            }
        }.onFailure {
            Log.w(TAG, "No se pudo adquirir el MulticastLock de Wi-Fi", it)
        }
    }

    /**
     * Libera el MulticastLock de Wi-Fi cuando el servicio NSD deja de estar activo.
     \*/
    private fun releaseMulticastLock() {
        runCatching {
            multicastLock?.takeIf { it.isHeld }?.let {
                it.release()
                Log.i(TAG, "MulticastLock de Wi-Fi liberado")
            }
        }.onFailure {
            Log.w(TAG, "No se pudo liberar el MulticastLock de Wi-Fi", it)
        }
    }

    /**
     * Resuelve la información de conexión (host, puerto y tipo de dispositivo) de un
     * servicio encontrado durante el descubrimiento. Si la dirección resuelta coincide
     * con la IP local de este dispositivo, el resultado se descarta por tratarse del
     * propio servicio publicado. En caso contrario, se agrega o actualiza en la lista
     * observable, de modo que la IP y el puerto vigentes reemplacen a los obsoletos.
     \*/
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
                    port = serviceInfo.port,
                    deviceType = resolveRemoteDeviceType(serviceInfo)
                )
                addOrUpdateDevice(device)
            }
        })
    }

    /**
     * Lee el tipo de dispositivo anunciado por el servicio remoto desde su registro
     * TXT. Si el atributo no está presente o no es reconocible, se asume
     * \[DeviceType.PHONE\] como valor seguro por defecto.
     \*/
    private fun resolveRemoteDeviceType(serviceInfo: NsdServiceInfo): DeviceType {
        val rawValue = serviceInfo.attributes[DEVICE_TYPE_ATTRIBUTE]
            ?.let { bytes -> String(bytes, Charsets.UTF_8) }
        return DeviceType.fromSerializedName(rawValue)
    }

    /**
     * Determina si la dirección \[candidateHost\] corresponde a la propia IP local
     * del dispositivo dentro de la red Wi-Fi actual.
     \*/
    private fun isLocalDeviceAddress(candidateHost: InetAddress): Boolean {
        val localIpAddress = getLocalIpAddress() ?: return false
        return candidateHost.hostAddress == localIpAddress
    }

    /**
     * Obtiene la dirección IP local del dispositivo dentro de la red Wi-Fi actual,
     * utilizando el \[WifiManager\] del sistema.
     \*/
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