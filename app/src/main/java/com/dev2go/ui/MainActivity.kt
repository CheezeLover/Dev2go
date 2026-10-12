package com.dev2go.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import com.dev2go.ui.theme.Dev2goTheme

class MainActivity : ComponentActivity() {

    private val viewModel: RuntimeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Dev2goTheme {
                // The picker lives here rather than in the ViewModel because
                // rememberLauncherForActivityResult needs a ComponentActivity owner; the
                // ViewModel only receives the resulting Uri.
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri: Uri? -> uri?.let(viewModel::onModelPicked) }

                LaunchedEffect(Unit) {
                    viewModel.pickModel = { picker.launch(arrayOf("*/*")) }
                }

                RuntimeScreen(viewModel)
            }
        }
    }
}