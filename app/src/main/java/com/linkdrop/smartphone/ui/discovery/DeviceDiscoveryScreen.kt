package com.linkdrop.smartphone.ui.discovery

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.linkdrop.smartphone.network.NsdDiscoveryManager
import com.linkdrop.smartphone.network.model.NetworkDevice
import com.linkdrop.smartphone.network.util.resolveLocalDeviceName
import com.linkdrop.smartphone.transfer.model.TransferProgress
import com.linkdrop.smartphone.transfer.net.FileReceiverManager
import com.linkdrop.smartphone.transfer.net.FileSenderManager

/** Puerto fijo en el que este dispositivo escucha conexiones entrantes. */
private const val LOCAL_SERVICE_PORT = 53317

/**
 * Pantalla mínima y temporal para validar el funcionamiento real del
 * descubrimiento de dispositivos y de las transferencias de archivo (envío y recepción).
 *
 * Al tocar un dispositivo de la lista se abre el selector de archivos del
 * sistema; el archivo elegido se envía automáticamente a ese dispositivo.
 * Sin estilos definitivos: este archivo será reemplazado por completo cuando
 * se implemente la interfaz final de la Home.
 *
 * @param onPickFile Función provista por la Activity para abrir el selector de
 *                    archivos del sistema. Recibe un callback que se invoca con
 *                    el [Uri] elegido por el usuario.
 */
@Composable
fun DeviceDiscoveryScreen(
    onPickFile: (onPicked: (Uri) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val localDeviceName = remember { resolveLocalDeviceName() }

    val discoveryManager = remember {
        NsdDiscoveryManager(
            context = context,
            localDeviceName = localDeviceName,
            localServicePort = LOCAL_SERVICE_PORT
        )
    }

    val senderManager = remember {
        FileSenderManager(
            context = context,
            localDeviceName = localDeviceName
        )
    }

    val receiverManager = remember {
        FileReceiverManager(
            context = context,
            listenPort = LOCAL_SERVICE_PORT
        )
    }

    val devices by discoveryManager.discoveredDevices.collectAsState()
    val sendProgress by senderManager.transferProgress.collectAsState()
    val receiveProgress by receiverManager.transferProgress.collectAsState()

    DisposableEffect(Unit) {
        discoveryManager.startPublishing()
        discoveryManager.startDiscovery()
        receiverManager.startListening()

        onDispose {
            discoveryManager.stopDiscovery()
            discoveryManager.stopPublishing()
            receiverManager.stopListening()
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Dispositivo local: $localDeviceName")

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        Text(text = "Envío: ${describeProgress(sendProgress)}")
        Text(text = "Recepción: ${describeProgress(receiveProgress)}")

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        Text(text = "Dispositivos encontrados: ${devices.size}")

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(devices) { device: NetworkDevice ->
                Text(
                    text = "${device.serviceName} — ${device.host.hostAddress}:${device.port}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .clickable {
                            onPickFile { pickedUri ->
                                senderManager.sendFile(pickedUri, device)
                            }
                        }
                )
            }
        }
    }
}

/**
 * Convierte un [TransferProgress] en un texto simple y legible para esta pantalla de prueba.
 */
private fun describeProgress(progress: TransferProgress): String {
    return when (progress) {
        is TransferProgress.Idle -> "sin actividad"
        is TransferProgress.InProgress ->
            "${progress.fileName} (${progress.percentage}%) ${if (progress.direction.name == "SENDING") "→" else "←"} ${progress.remoteDeviceName}"
        is TransferProgress.Completed ->
            "completado: ${progress.fileName} (${progress.totalBytes} bytes)"
        is TransferProgress.Failed ->
            "error con ${progress.fileName}: ${progress.reason}"
    }
}