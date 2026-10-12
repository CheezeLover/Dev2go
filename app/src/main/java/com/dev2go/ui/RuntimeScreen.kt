package com.dev2go.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dev2go.ui.theme.Ground
import com.dev2go.ui.theme.Negative
import com.dev2go.ui.theme.Outline
import com.dev2go.ui.theme.Positive
import com.dev2go.ui.theme.SurfaceHigh
import com.dev2go.ui.theme.TextFaint
import com.dev2go.ui.theme.TextPrimary
import com.dev2go.ui.theme.TextSecondary

/**
 * Test harness for the on-device LLM runtime.
 *
 * This is not the agent UI. It exists to answer one question on real hardware that no
 * amount of x86-64 testing can: does the embedded llama.cpp server load a GGUF off phone
 * storage, bind a port, and answer a request. Everything here is in service of that.
 */
@Composable
fun RuntimeScreen(viewModel: RuntimeViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(containerColor = Ground) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text("Dev2go", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "on-device LLM runtime — test harness",
                    color = TextFaint,
                    fontSize = 13.sp,
                )
            }

            Section("Runtime") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(if (viewModel.nativeAvailable) Positive else Negative)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (viewModel.nativeAvailable) "libdev2go.so loaded" else "native runtime unavailable",
                        color = TextPrimary,
                        fontSize = 14.sp,
                    )
                }
                KeyValue("ABIs", viewModel.abis)
                KeyValue("Threads", "${viewModel.threads}")
                viewModel.backend?.let { KeyValue("llama.cpp", it) }
                viewModel.nativeError?.let { KeyValue("Error", it, Negative) }
            }

            Section("Model") {
                if (state.imported.isEmpty()) {
                    Text(
                        "No model yet. Pick a GGUF — it is copied into app storage so it stays " +
                            "after the picker closes. Nothing leaves the device and no storage " +
                            "permission is requested.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                    )
                } else {
                    state.imported.forEach { file ->
                        val selected = file.name == state.selected?.name
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (selected) SurfaceHigh else Color.Transparent,
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(vertical = 8.dp, horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(file.name, color = TextPrimary, fontSize = 14.sp)
                                Text(
                                    "%.1f MB".format(file.length() / 1_048_576.0),
                                    color = TextFaint,
                                    fontSize = 12.sp,
                                )
                            }
                            OutlinedButton(
                                onClick = { viewModel.select(file) },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                            ) { Text(if (selected) "Selected" else "Select", fontSize = 12.sp) }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = viewModel.pickModel,
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceHigh, contentColor = TextPrimary),
                ) { Text("Pick a GGUF…", fontSize = 14.sp) }
            }

            Section("Server") {
                when (val s = state.server) {
                    is com.dev2go.runtime.ServerState.Idle -> Text("stopped", color = TextFaint, fontSize = 13.sp)
                    is com.dev2go.runtime.ServerState.Starting ->
                        Text("loading ${s.model}…", color = TextSecondary, fontSize = 13.sp)
                    is com.dev2go.runtime.ServerState.Running -> {
                        KeyValue("Model", s.model, Positive)
                        KeyValue("OpenAI-compatible", s.baseUrl + "/v1", Positive)
                        Text("/v1/models", color = TextFaint, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            s.modelsJson.take(400),
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    is com.dev2go.runtime.ServerState.Failed ->
                        Text(s.reason, color = Negative, fontSize = 13.sp)
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { viewModel.start() },
                        enabled = state.selected != null && !state.busy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SurfaceHigh,
                            contentColor = TextPrimary,
                            disabledContainerColor = SurfaceHigh,
                            disabledContentColor = TextFaint,
                        ),
                    ) { Text("Start", fontSize = 14.sp) }
                    OutlinedButton(
                        onClick = { viewModel.stop() },
                        enabled = !state.busy,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    ) { Text("Stop", fontSize = 14.sp) }
                }
            }

            Text(
                "This build proves the runtime only. The agent — opencode cross-compiled to " +
                    "arm64 — and the OpenChamber UI are not wired in yet.",
                color = TextFaint,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceHigh, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title.uppercase(), color = TextFaint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun KeyValue(key: String, value: String, valueColor: Color = TextSecondary) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, color = TextFaint, fontSize = 12.sp, modifier = Modifier.width(120.dp))
        Text(value, color = valueColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Dot(color: Color) {
    Spacer(
        Modifier
            .background(color, RoundedCornerShape(50))
            .size(8.dp),
    )
}