package com.linkdrop.smartphone.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linkdrop.smartphone.R
import com.linkdrop.smartphone.network.model.DeviceType
import com.linkdrop.smartphone.network.model.NetworkDevice

/**
 * Tarjeta redondeada que representa un dispositivo LinkDrop descubierto en la
 * red local. Muestra el icono según el tipo de dispositivo, su nombre, su
 * estado de disponibilidad y un chevron indicador de acción.
 *
 * @param device Dispositivo descubierto que se representa.
 * @param onClick Callback invocado al tocar la tarjeta.
 \*/
@Composable
fun DeviceCard(
    device: NetworkDevice,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val deviceIconRes = when (device.deviceType) {
        DeviceType.PHONE -> R.drawable.ic_device_phone
        DeviceType.TABLET -> R.drawable.ic_device_tablet
        DeviceType.TV -> R.drawable.ic_device_tv
    }

    val deviceIconDescription = when (device.deviceType) {
        DeviceType.PHONE -> stringResource(R.string.device_type_phone)
        DeviceType.TABLET -> stringResource(R.string.device_type_tablet)
        DeviceType.TV -> stringResource(R.string.device_type_tv)
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(28.dp)
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 21.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(deviceIconRes),
                    contentDescription = deviceIconDescription,
                    modifier = Modifier.size(30.dp),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSecondaryContainer)
                )
            }

            Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                Text(
                    text = device.serviceName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiary)
                    )

                    Text(
                        text = stringResource(R.string.available),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            Image(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(28.dp),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)
            )
        }
    }
}