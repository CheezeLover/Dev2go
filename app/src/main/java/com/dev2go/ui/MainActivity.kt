package com.dev2go.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dev2go.ui.theme.Dev2goTheme
import com.dev2go.ui.theme.Ground
import com.dev2go.ui.theme.Surface
import com.dev2go.ui.theme.TextFaint
import com.dev2go.ui.theme.TextPrimary

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Dev2goTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()

                val modelPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri: Uri? -> uri?.let(viewModel::importModel) }
                val agentPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri: Uri? -> uri?.let(viewModel::importAgent) }

                Dev2goApp(state, viewModel, onPickModel = { modelPicker.launch(arrayOf("*/*")) }, onPickAgent = {
                    agentPicker.launch(arrayOf("*/*"))
                })
            }
        }
    }
}

@Composable
private fun Dev2goApp(
    state: UiState,
    viewModel: AppViewModel,
    onPickModel: () -> Unit,
    onPickAgent: () -> Unit,
) {
    Scaffold(
        containerColor = Ground,
        bottomBar = { TabBar(state.tab) { viewModel.select(it) } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (state.tab) {
                Tab.Chat -> ChatScreen(viewModel, state)
                Tab.Models -> ModelsScreen(viewModel, state, onPickModel)
                Tab.Runtime -> RuntimeScreen(viewModel, state)
            }
        }
    }
}

@Composable
private fun TabBar(current: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Surface)
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Tab.entries.forEach { tab ->
            val active = tab == current
            Column(
                Modifier
                    .weight(1f)
                    .background(
                        if (active) com.dev2go.ui.theme.SurfaceHigh else Ground,
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { onSelect(tab) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    tab.name,
                    color = if (active) TextPrimary else TextFaint,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}