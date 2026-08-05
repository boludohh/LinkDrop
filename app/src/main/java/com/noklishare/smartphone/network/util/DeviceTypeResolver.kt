package com.noklishare.smartphone.network.util

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import com.noklishare.smartphone.network.model.DeviceType

/** Ancho mínimo de pantalla, en dp, a partir del cual un dispositivo se considera tablet. */
private const val TABLET_MIN_SMALLEST_WIDTH_DP = 600

/**
 * Detecta el [DeviceType] del dispositivo local actual.
 *
 * La detección de televisor se basa en [UiModeManager], el mecanismo oficial
 * de Android para identificar si la aplicación corre sobre Android TV. La
 * distinción entre teléfono y tablet se basa en el ancho mínimo de pantalla
 * en dp ([android.content.res.Configuration.smallestScreenWidthDp]), siguiendo
 * el criterio oficial recomendado por Android para diferenciar ambos formatos.
 */
fun resolveLocalDeviceType(context: Context): DeviceType {
    val appContext = context.applicationContext

    val uiModeManager = appContext.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    val isTelevision = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

    if (isTelevision) {
        return DeviceType.TV
    }

    val smallestScreenWidthDp = appContext.resources.configuration.smallestScreenWidthDp

    return if (smallestScreenWidthDp >= TABLET_MIN_SMALLEST_WIDTH_DP) {
        DeviceType.TABLET
    } else {
        DeviceType.PHONE
    }
}