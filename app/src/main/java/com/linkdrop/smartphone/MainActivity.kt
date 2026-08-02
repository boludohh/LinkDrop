package com.linkdrop.smartphone

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
import com.linkdrop.smartphone.ui.discovery.DeviceDiscoveryScreen
import com.linkdrop.smartphone.ui.theme.MyComposeApplicationTheme

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    /** Callback invocado cuando el usuario elige un archivo desde el selector del sistema. */
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
     * Abre el selector de archivos del sistema. Al elegir un archivo, se invoca [onPicked].
     * Si el usuario cancela la selección, [onPicked] no se invoca.
     */
    private fun launchFilePicker(onPicked: (Uri) -> Unit) {
        onFilePicked = onPicked
        filePickerLauncher.launch(arrayOf("*/*"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyComposeApplicationTheme {
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