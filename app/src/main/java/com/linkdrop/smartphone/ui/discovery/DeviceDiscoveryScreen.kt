package com.linkdrop.smartphone.ui.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

/** Puerto fijo en el que este dispositivo escucha conexiones entrantes (compartido con la Fase 2). */
private const val LOCAL_SERVICE_PORT = 53317

/**
 * Pantalla mínima y temporal para validar el funcionamiento real de [NsdDiscoveryManager].
 *
 * Publica este dispositivo en la red y muestra en una lista simple los demás
 * dispositivos LinkDrop descubiertos, sin estilos definitivos. Este archivo será
 * reemplazado por completo cuando se implemente la interfaz final de la Home.
 */
@Composable
fun DeviceDiscoveryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val discoveryManager = remember {
        NsdDiscoveryManager(
            context = context,
            localDeviceName = resolveLocalDeviceName(),
            localServicePort = LOCAL_SERVICE_PORT
        )
    }

    val devices by discoveryManager.discoveredDevices.collectAsState()

    DisposableEffect(Unit) {
        discoveryManager.startPublishing()
        discoveryManager.startDiscovery()

        onDispose {
            discoveryManager.stopDiscovery()
            discoveryManager.stopPublishing()
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Dispositivos encontrados: ${devices.size}")

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(devices) { device: NetworkDevice ->
                Text(text = "${device.serviceName} — ${device.host.hostAddress}:${device.port}")
            }
        }
    }
}