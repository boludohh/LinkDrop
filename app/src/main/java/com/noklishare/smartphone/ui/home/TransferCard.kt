package com.noklishare.smartphone.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noklishare.smartphone.R
import com.noklishare.smartphone.transfer.model.TransferDirection
import com.noklishare.smartphone.transfer.model.TransferProgress
import com.noklishare.smartphone.util.TransferFormatting

/**
 * Tarjeta de progreso de una transferencia activa o recién finalizada.
 *
 * En curso muestra dirección (envío/recepción), dispositivo remoto, nombre del
 * archivo, barra de progreso, porcentaje, velocidad y tiempo estimado.
 * Al finalizar (éxito o fallo) muestra el resultado con la opción de descartar.
 *
 * @param progress Estado de la transferencia a representar.
 * @param onDismiss Callback invocado al descartar una transferencia finalizada.
 \*/
@Composable
fun TransferCard(
    progress: TransferProgress,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (progress is TransferProgress.Idle) {
        return
    }

    val context = LocalContext.current

    Surface(
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
        when (progress) {
            is TransferProgress.InProgress -> {
                val isSending = progress.direction == TransferDirection.SENDING

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(
                                if (isSending) R.drawable.ic_transfer_send else R.drawable.ic_transfer_receive
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSecondaryContainer)
                        )
                    }

                    Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(
                            text = stringResource(
                                if (isSending) R.string.sending_to else R.string.receiving_from,
                                progress.remoteDeviceName
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Text(
                            text = "${progress.fileName} · ${TransferFormatting.bytes(context, progress.totalBytes)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )

                        LinearProgressIndicator(
                            progress = { progress.percentage / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 6.dp)
                        ) {
                            Text(
                                text = "${progress.percentage} %",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (progress.speedBytesPerSecond > 0.0) {
                                Text(
                                    text = TransferFormatting.speed(context, progress.speedBytesPerSecond),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (progress.estimatedRemainingMillis >= 0L) {
                                Text(
                                    text = TransferFormatting.duration(progress.estimatedRemainingMillis),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            is TransferProgress.Completed -> {
                TransferEndStateContent(
                    iconRes = R.drawable.ic_done,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    title = stringResource(R.string.transfer_completed),
                    detail = progress.fileName,
                    onDismiss = onDismiss
                )
            }

            is TransferProgress.Failed -> {
                TransferEndStateContent(
                    iconRes = R.drawable.ic_error,
                    iconTint = MaterialTheme.colorScheme.error,
                    title = stringResource(R.string.transfer_failed),
                    detail = progress.reason,
                    onDismiss = onDismiss
                )
            }

            else -> Unit
        }
    }
}

/**
 * Contenido compacto de una transferencia finalizada (éxito o fallo), con
 * icono de resultado, título, detalle y botón para descartar la tarjeta.
 \*/
@Composable
private fun TransferEndStateContent(
    iconRes: Int,
    iconTint: Color,
    title: String,
    detail: String,
    onDismiss: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            colorFilter = ColorFilter.tint(iconTint)
        )

        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        TextButton(onClick = onDismiss) {
            Text(text = stringResource(R.string.dismiss))
        }
    }
}