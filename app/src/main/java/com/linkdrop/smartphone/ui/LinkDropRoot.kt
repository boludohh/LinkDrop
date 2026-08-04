package com.linkdrop.smartphone.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
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

/** Duración de la transición entre la Home y Ajustes, en milisegundos. \*/
private const val NAVIGATION_TRANSITION_MS = 400

/**
 * Composable raíz de la aplicación: posee los gestores de red y transferencia
 * (para que sobrevivan a la navegación entre pantallas) y resuelve qué pantalla
 * está visible: la Home o Ajustes, con una transición horizontal fluida
 * entre ambas.
 *
 * Pinta el fondo a pantalla completa con el color de fondo del esquema activo
 * y aplica los insets de las barras del sistema (estado y navegación), de modo
 * que todo el contenido de cualquier pantalla se dibuje siempre por debajo de
 * la barra de estado y por encima de la barra de navegación, tanto en
 * dispositivos con modo edge-to-edge impuesto (Android 15+) como en versiones
 * anteriores, donde estos insets valen cero y no alteran el diseño.
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            val resolvedName = localDeviceName
            if (resolvedName == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
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

                AnimatedContent(
                    targetState = showingSettings,
                    transitionSpec = {
                        if (targetState) {
                            // Ida a Ajustes: entra desde la derecha y la Home
                            // sale con un desplazamiento leve hacia la izquierda.
                            (slideInHorizontally(
                                animationSpec = tween(
                                    durationMillis = NAVIGATION_TRANSITION_MS,
                                    easing = FastOutSlowInEasing
                                ),
                                initialOffsetX = { fullWidth -> fullWidth }
                            ) + fadeIn(
                                animationSpec = tween(
                                    durationMillis = NAVIGATION_TRANSITION_MS
                                )
                            )) togetherWith (slideOutHorizontally(
                                animationSpec = tween(
                                    durationMillis = NAVIGATION_TRANSITION_MS,
                                    easing = FastOutSlowInEasing
                                ),
                                targetOffsetX = { fullWidth -> -fullWidth / 3 }
                            ) + fadeOut(
                                animationSpec = tween(
                                    durationMillis = NAVIGATION_TRANSITION_MS
                                )
                            ))
                        } else {
                            // Regreso a la Home: movimiento inverso.
                            (slideInHorizontally(
                                animationSpec = tween(
                                    durationMillis = NAVIGATION_TRANSITION_MS,
                                    easing = FastOutSlowInEasing
                                ),
                                initialOffsetX = { fullWidth -> -fullWidth / 3 }
                            ) + fadeIn(
                                animationSpec = tween(
                                    durationMillis = NAVIGATION_TRANSITION_MS
                                )
                            )) togetherWith (slideOutHorizontally(
                                animationSpec = tween(
                                    durationMillis = NAVIGATION_TRANSITION_MS,
                                    easing = FastOutSlowInEasing
                                ),
                                targetOffsetX = { fullWidth -> fullWidth }
                            ) + fadeOut(
                                animationSpec = tween(
                                    durationMillis = NAVIGATION_TRANSITION_MS
                                )
                            ))
                        }
                    },
                    label = "LinkDropMainNavigation",
                    modifier = Modifier.fillMaxSize()
                ) { target ->
                    if (target) {
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
        }
    }
}