package com.noklishare.smartphone.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import com.noklishare.smartphone.R
import com.noklishare.smartphone.network.model.NetworkDevice
import com.noklishare.smartphone.transfer.model.IncomingTransferRequest
import com.noklishare.smartphone.transfer.model.TransferProgress
import kotlinx.coroutines.flow.StateFlow

/** Duración de las animaciones de aparición/desaparición de tarjetas, en ms. \*/
private const val CARD_ANIMATION_MS = 300

/**
 * Pantalla principal de NokliShare.
 *
 * Muestra la cabecera con el nombre de la aplicación y el acceso circular a
 * Ajustes; debajo, las tarjetas dinámicas de solicitud entrante y de
 * transferencias en curso/finalizadas, y finalmente el estado vacío o la
 * lista de dispositivos activos en la red local.
 *
 * @param availableDevices Flujo con los dispositivos descubiertos verificados como activos.
 * @param senderProgress Flujo con el estado de la transferencia de envío.
 * @param receiverProgress Flujo con el estado de la transferencia de recepción.
 * @param incomingRequest Flujo con la solicitud entrante pendiente, si la hay.
 * @param onDeviceClick Callback invocado al tocar una tarjeta de dispositivo.
 * @param onOpenSettings Callback invocado al tocar el botón circular de Ajustes.
 * @param onAcceptIncoming Callback invocado al aceptar una transferencia entrante.
 * @param onRejectIncoming Callback invocado al rechazar una transferencia entrante.
 * @param onDismissSenderTransfer Callback invocado al descartar la tarjeta de envío finalizada.
 * @param onDismissReceiverTransfer Callback invocado al descartar la tarjeta de recepción finalizada.
 \*/
@Composable
fun HomeScreen(
    availableDevices: StateFlow<List<NetworkDevice>>,
    senderProgress: StateFlow<TransferProgress>,
    receiverProgress: StateFlow<TransferProgress>,
    incomingRequest: StateFlow<IncomingTransferRequest?>,
    onDeviceClick: (NetworkDevice) -> Unit,
    onOpenSettings: () -> Unit,
    onAcceptIncoming: () -> Unit,
    onRejectIncoming: () -> Unit,
    onDismissSenderTransfer: () -> Unit,
    onDismissReceiverTransfer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val devices by availableDevices.collectAsState()
    val senderState by senderProgress.collectAsState()
    val receiverState by receiverProgress.collectAsState()
    val request by incomingRequest.collectAsState()

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

        AnimatedVisibility(
            visible = request != null,
            enter = expandVertically(tween(CARD_ANIMATION_MS)) + fadeIn(tween(CARD_ANIMATION_MS)),
            exit = shrinkVertically(tween(CARD_ANIMATION_MS)) + fadeOut(tween(CARD_ANIMATION_MS)),
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            val currentRequest = request
            if (currentRequest != null) {
                IncomingTransferCard(
                    request = currentRequest,
                    onAccept = onAcceptIncoming,
                    onReject = onRejectIncoming,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = senderState !is TransferProgress.Idle,
            enter = expandVertically(tween(CARD_ANIMATION_MS)) + fadeIn(tween(CARD_ANIMATION_MS)),
            exit = shrinkVertically(tween(CARD_ANIMATION_MS)) + fadeOut(tween(CARD_ANIMATION_MS)),
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            TransferCard(
                progress = senderState,
                onDismiss = onDismissSenderTransfer,
                modifier = Modifier.padding(top = 16.dp)
            )
        }

        AnimatedVisibility(
            visible = receiverState !is TransferProgress.Idle,
            enter = expandVertically(tween(CARD_ANIMATION_MS)) + fadeIn(tween(CARD_ANIMATION_MS)),
            exit = shrinkVertically(tween(CARD_ANIMATION_MS)) + fadeOut(tween(CARD_ANIMATION_MS)),
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            TransferCard(
                progress = receiverState,
                onDismiss = onDismissReceiverTransfer,
                modifier = Modifier.padding(top = 16.dp)
            )
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