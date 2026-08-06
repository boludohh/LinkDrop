package com.noklishare.smartphone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.animation.PathInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.noklishare.smartphone.settings.LocaleRepository
import com.noklishare.smartphone.ui.LinkDropRoot
import com.noklishare.smartphone.ui.theme.LinkDropTheme
import com.noklishare.smartphone.util.applyAppLocale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Locale

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"

        /** Duración de la animación de salida del splash, en milisegundos. \*/
        private const val SPLASH_EXIT_ANIMATION_MS = 400L
    }

    /**
     * Indica que el splash comenzó su animación de salida, para que el
     * contenido de la Home inicie su animación de entrada en paralelo.
     \*/
    private val splashExiting = mutableStateOf(false)

    /** Callback invocado cuando el usuario elige un archivo desde el selector del sistema. \*/
    private var onFilePicked: ((Uri) -> Unit)? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onFilePicked?.invoke(uri)
        }
        onFilePicked = null
    }

    /** Resultado de la solicitud del permiso de almacenamiento en Android 8/9. \*/
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.i(TAG, "Permiso de almacenamiento concedido")
        } else {
            Log.w(
                TAG,
                "Permiso de almacenamiento denegado; la recepción de archivos fallará en este dispositivo"
            )
        }
    }

    /**
     * Aplica el idioma guardado en DataStore antes de inflar cualquier
     * recurso o composable, para que todos los textos se resuelvan en el
     * idioma elegido por el usuario y no en el del sistema.
     *
     * La lectura es síncrona porque [attachBaseContext] no admite
     * ejecución suspendida; el primer acceso a DataStore es breve y
     * queda cacheado en memoria para las siguientes recreaciones.
     \*/
    override fun attachBaseContext(newBase: Context) {
        val savedLanguageCode = runBlocking {
            LocaleRepository(newBase).selectedLanguageCode.first()
        }
        super.attachBaseContext(
            newBase.applyAppLocale(Locale.forLanguageTag(savedLanguageCode))
        )
    }

    /**
     * Abre el selector de archivos del sistema. Al elegir un archivo, se invoca \[onPicked\].
     * Si el usuario cancela la selección, \[onPicked\] no se invoca.
     \*/
    private fun launchFilePicker(onPicked: (Uri) -> Unit) {
        onFilePicked = onPicked
        filePickerLauncher.launch(arrayOf("*/*"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Anima la salida del splash con la misma transición que usa la app
        // entre pantallas (desplazamiento hacia la izquierda con fundido),
        // mientras el contenido de la Home entra desde la derecha.
        splashScreen.setOnExitAnimationListener { splashProvider ->
            splashExiting.value = true

            val splashView = splashProvider.view
            splashView.animate()
                .translationXBy(-splashView.width / 3f)
                .alpha(0f)
                .setDuration(SPLASH_EXIT_ANIMATION_MS)
                .setInterpolator(PathInterpolator(0.4f, 0f, 0.2f, 1f))
                .withEndAction { splashProvider.remove() }
                .start()
        }

        requestLegacyStoragePermissionIfNeeded()

        setContent {
            LinkDropTheme {
                LinkDropRoot(
                    onPickFile = ::launchFilePicker,
                    splashExiting = splashExiting
                )
            }
        }
    }

    /**
     * En Android 8/9 (API 28 y anteriores) escribir en la carpeta pública de
     * Descargas requiere el permiso de almacenamiento en tiempo de ejecución;
     * se solicita una vez al arrancar. En Android 10+ no es necesario.
     \*/
    private fun requestLegacyStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}