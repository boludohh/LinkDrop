package com.linkdrop.smartphone.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linkdrop.smartphone.R
import com.linkdrop.smartphone.network.model.NetworkDevice
import kotlinx.coroutines.flow.StateFlow

/**
 * Pantalla principal de LinkDrop.
 *
 * Muestra la cabecera con el nombre de la aplicación y el acceso circular a
 * Ajustes, y debajo el estado vacío o la lista de dispositivos que están
 * realmente activos en la red local. Las tarjetas de dispositivos se extienden
 * un poco más hacia los laterales que la cabecera para ganar presencia visual.
 *
 * @param availableDevices Flujo con los dispositivos descubiertos que fueron
 * verificados como activos por el monitor de disponibilidad.
 * @param onDeviceClick Callback invocado al tocar una tarjeta de dispositivo.
 * @param onOpenSettings Callback invocado al tocar el botón circular de Ajustes.
 \*/
@Composable
fun HomeScreen(
    availableDevices: StateFlow<List<NetworkDevice>>,
    onDeviceClick: (NetworkDevice) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val devices by availableDevices.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(vertical = 20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        shape = CircleShape
                    )
                    .clickable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.ic_settings),
                    contentDescription = stringResource(R.string.cd_open_settings),
                    modifier = Modifier.size(26.dp),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)
                )
            }
        }

        if (devices.isEmpty()) {
            EmptyDevicesState(modifier = Modifier.weight(1f))
        } else {
            Text(
                text = stringResource(R.string.devices_on_network),
                style = MaterialTheme.typography.bodyLarge,
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(top = 28.dp, bottom = 12.dp)
            )

            LazyColumn(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp)
            ) {
                items(devices) { device ->
                    DeviceCard(
                        device = device,
                        onClick = { onDeviceClick(device) }
                    )
                }
            }
        }
    }
}