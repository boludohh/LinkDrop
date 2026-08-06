package com.noklishare.smartphone.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import com.noklishare.smartphone.network.DeviceAvailabilityMonitor
import com.noklishare.smartphone.network.NsdDiscoveryManager
import com.noklishare.smartphone.network.model.NetworkDevice
import com.noklishare.smartphone.network.util.resolveLocalDeviceName
import com.noklishare.smartphone.network.util.resolveLocalDeviceType
import com.noklishare.smartphone.settings.DeviceNameRepository
import com.noklishare.smartphone.transfer.net.FileReceiverManager
import com.noklishare.smartphone.transfer.net.FileSenderManager
import com.noklishare.smartphone.transfer.net.IncomingTransferCoordinator
import com.noklishare.smartphone.ui.home.HomeScreen
import com.noklishare.smartphone.ui.settings.SettingsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** Puerto fijo en el que este dispositivo escucha conexiones entrantes. \*/
private const val LOCAL_SERVICE_PORT = 53317

/** Duración de las transiciones entre pantallas, en milisegundos. \*/
private const val NAVIGATION_TRANSITION_MS = 400

/**
 * Espera máxima de seguridad antes de iniciar la animación de entrada de la
 * Home, por si el listener de salida del splash no se disparara (por ejemplo,
 * en una recreación de la Activity).
 \*/
private const val SPLASH_ENTER_FALLBACK_MS = 1000L

/**
 * Composable raíz de la aplicación: posee los gestores de red y transferencia
 * (para que sobrevivan a la navegación entre pantallas) y resuelve qué pantalla
 * está visible: la Home o Ajustes, con una transición horizontal fluida
 * entre ambas.
 *
 * Además, reproduce esa misma transición al entrar desde el splash screen:
 * el contenido se compone desde el primer frame detrás del splash (para que
 * la animación no cargue con el costo de inflar el árbol completo) y, cuando
 * el splash inicia su salida, la Home se desliza desde la derecha con fundido
 * animando únicamente transformación y opacidad, que son operaciones baratas.
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
 * @param splashExiting Estado que la Activity activa cuando el splash comienza
 * su animación de salida, para disparar la entrada animada de la Home.
 \*/
@Composable
fun LinkDropRoot(
    onPickFile: (onPicked: (Uri) -> Unit) -> Unit,
    splashExiting: State<Boolean>
) {
    val context = LocalContext.current

    // El nombre local puede tardar un instante en resolverse porque primero
    // se consulta si el usuario definió un nombre personalizado en DataStore.
    var localDeviceName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val repository = DeviceNameRepository(context)
        val customName = repository.customDeviceName.first()
        localDeviceName = customName ?: resolveLocalDeviceName()
    }

    // La entrada de la Home se dispara cuando el splash empieza a salir.
    var enterStarted by remember { mutableStateOf(false) }
    val exiting by splashExiting

    LaunchedEffect(exiting) {
        if (exiting) {
            enterStarted = true
        }
    }

    // Red de seguridad: si el listener de salida del splash no se disparara,
    // el contenido entra igualmente tras una espera breve.
    LaunchedEffect(Unit) {
        delay(SPLASH_ENTER_FALLBACK_MS)
        enterStarted = true
    }

    // 1f = contenido totalmente fuera de pantalla y transparente;
    // 0f = contenido en su posición final y totalmente visible.
    val enterFraction by animateFloatAsState(
        targetValue = if (enterStarted) 0f else 1f,
        animationSpec = tween(
            durationMillis = NAVIGATION_TRANSITION_MS,
            easing = FastOutSlowInEasing
        ),
        label = "homeEnterTransition"
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .graphicsLayer {
                    translationX = size.width * enterFraction
                    alpha = 1f - enterFraction
                }
        ) {
            val resolvedName = localDeviceName
            if (resolvedName != null) {
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

                val incomingTransferCoordinator = remember { IncomingTransferCoordinator() }

                val receiverManager = remember {
                    FileReceiverManager(
                        context = context,
                        listenPort = LOCAL_SERVICE_PORT,
                        onIncomingFileRequest = { remoteDeviceName, fileName ->
                            incomingTransferCoordinator.awaitUserDecision(remoteDeviceName, fileName)
                        }
                    )
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
                            senderProgress = senderManager.transferProgress,
                            receiverProgress = receiverManager.transferProgress,
                            incomingRequest = incomingTransferCoordinator.pendingRequest,
                            onDeviceClick = { device: NetworkDevice ->
                                onPickFile { pickedUri ->
                                    senderManager.sendFile(pickedUri, device)
                                }
                            },
                            onOpenSettings = { showingSettings = true },
                            onAcceptIncoming = { incomingTransferCoordinator.accept() },
                            onRejectIncoming = { incomingTransferCoordinator.reject() },
                            onDismissSenderTransfer = { senderManager.dismissTransfer() },
                            onDismissReceiverTransfer = { receiverManager.dismissTransfer() }
                        )
                    }
                }
            }
            // Mientras el nombre del dispositivo se resuelve solo se muestra
            // el fondo: la resolución es casi instantánea y queda cubierta
            // por la propia animación de entrada.
        }
    }
}