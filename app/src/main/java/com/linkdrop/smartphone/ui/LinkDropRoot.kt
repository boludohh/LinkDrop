package com.linkdrop.smartphone.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.linkdrop.smartphone.network.DeviceAvailabilityMonitor
import com.linkdrop.smartphone.network.NsdDiscoveryManager
import com.linkdrop.smartphone.network.model.NetworkDevice
import com.linkdrop.smartphone.network.util.resolveLocalDeviceName
import com.linkdrop.smartphone.network.util.resolveLocalDeviceType
import com.linkdrop.smartphone.settings.DeviceNameRepository
import com.linkdrop.smartphone.transfer.net.FileReceiverManager
import com.linkdrop.smartphone.transfer.net.FileSenderManager
import com.linkdrop.smartphone.ui.home.HomeScreen
import com.linkdrop.smartphone.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.first

/** Puerto fijo en el que este dispositivo escucha conexiones entrantes. \*/
private const val LOCAL_SERVICE_PORT = 53317

/**
 * Composable raíz de la aplicación: posee los gestores de red y transferencia
 * (para que sobrevivan a la navegación entre pantallas) y resuelve qué pantalla
 * está visible: la Home o Ajustes.
 *
 * Pinta además el fondo a pantalla completa con el color de fondo del esquema
 * activo, para que toda la interfaz siga al modo claro/oscuro resuelto por el
 * tema y no al fondo fijo de la ventana de Android.
 *
 * @param onPickFile Función provista por la Activity para abrir el selector de
 * archivos del sistema. Recibe un callback que se invoca con el \[Uri\] elegido.
 \*/
@Composable
fun LinkDropRoot(onPickFile: (onPicked: (Uri) -> Unit) -> Unit) {
    val context = LocalContext.current

    // El nombre local puede tardar un instante en resolverse porque primero
    // se consulta si el usuario definió un nombre personalizado en DataStore.
    var localDeviceName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val repository = DeviceNameRepository(context)
        val customName = repository.customDeviceName.first()
        localDeviceName = customName ?: resolveLocalDeviceName()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        val resolvedName = localDeviceName
        if (resolvedName == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Surface
        }

        val discoveryManager = remember(resolvedName) {
            NsdDiscoveryManager(
                context = context,
                localDeviceName = resolvedName,
                localServicePort = LOCAL_SERVICE_PORT,
                localDeviceType = resolveLocalDeviceType(context)
            )
        }

        val senderManager = remember(resolvedName) {
            FileSenderManager(context = context, localDeviceName = resolvedName)
        }

        val receiverManager = remember {
            FileReceiverManager(context = context, listenPort = LOCAL_SERVICE_PORT)
        }

        val availabilityMonitor = remember(discoveryManager) {
            DeviceAvailabilityMonitor(discoveryManager.discoveredDevices)
        }

        DisposableEffect(resolvedName) {
            discoveryManager.startPublishing()
            discoveryManager.startDiscovery()
            receiverManager.startListening()
            availabilityMonitor.start()

            onDispose {
                availabilityMonitor.stop()
                discoveryManager.stopDiscovery()
                discoveryManager.stopPublishing()
                receiverManager.stopListening()
            }
        }

        var showingSettings by remember { mutableStateOf(false) }

        if (showingSettings) {
            SettingsScreen(onBack = { showingSettings = false })
        } else {
            HomeScreen(
                availableDevices = availabilityMonitor.availableDevices,
                onDeviceClick = { device: NetworkDevice ->
                    onPickFile { pickedUri ->
                        senderManager.sendFile(pickedUri, device)
                    }
                },
                onOpenSettings = { showingSettings = true }
            )
        }
    }
}