package com.linkdrop.smartphone

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.linkdrop.smartphone.settings.LocaleRepository
import com.linkdrop.smartphone.ui.discovery.DeviceDiscoveryScreen
import com.linkdrop.smartphone.ui.theme.LinkDropTheme
import com.linkdrop.smartphone.util.applyAppLocale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Locale

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

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
        super.onCreate(savedInstanceState)
        setContent {
            LinkDropTheme {
                Scaffold { innerPadding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        DeviceDiscoveryScreen(
                            onPickFile = ::launchFilePicker
                        )
                    }
                }
            }
        }
    }
}