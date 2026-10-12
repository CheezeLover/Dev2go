package com.dev2go.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dev2go.agent.AgentProcess
import com.dev2go.agent.AgentState
import com.dev2go.runtime.LlmServer
import com.dev2go.runtime.OllamaFacade
import com.dev2go.runtime.ServerState
import com.dev2go.ui.theme.Ground
import com.dev2go.ui.theme.Negative
import com.dev2go.ui.theme.Positive
import com.dev2go.ui.theme.SurfaceHigh
import com.dev2go.ui.theme.TextFaint
import com.dev2go.ui.theme.TextPrimary
import com.dev2go.ui.theme.TextSecondary

/** Where the agent, the model runtime and the Ollama facade each stand. */
@Composable
fun RuntimeScreen(viewModel: AppViewModel, state: UiState) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Ground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard("Native runtime") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (viewModel.nativeAvailable) Positive else Negative)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (viewModel.nativeAvailable) "libdev2go.so loaded" else "unavailable",
                    color = TextPrimary,
                    fontSize = 14.sp,
                )
            }
            KeyValue("ABIs", viewModel.abis)
            KeyValue("Threads", LlmServer.recommendedThreads().toString())
            viewModel.backend?.let { KeyValue("llama.cpp", it) }
            viewModel.nativeError?.let { KeyValue("Error", it, Negative) }
        }

        SectionCard("Agent") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(
                    when (val a = state.agentState) {
                        is AgentState.Running -> Positive
                        is AgentState.Failed -> Negative
                        is AgentState.Starting -> TextSecondary
                        AgentState.Absent -> TextFaint
                    }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when (val a = state.agentState) {
                        is AgentState.Running -> "running on ${a.baseUrl}"
                        is AgentState.Failed -> a.reason
                        is AgentState.Starting -> "starting…"
                        AgentState.Absent -> "stopped"
                    },
                    color = TextPrimary,
                    fontSize = 14.sp,
                )
            }

            if (!viewModel.agentInstalled) {
                Text(
                    "No opencode binary for this device yet. Build one with scripts/build-agent.sh " +
                        "and install it from the Models tab, or drop opencode-arm64 into the app's files.",
                    color = TextFaint,
                    fontSize = 11.sp,
                )
            }

            KeyValue("Port", AgentProcess.DEFAULT_PORT.toString())
            KeyValue("Auth", "HTTP Basic — password generated per launch")

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { viewModel.startAgent() },
                    enabled = state.agentState !is AgentState.Running && viewModel.agentInstalled,
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceHigh, contentColor = TextPrimary),
                ) { Text("Start agent", fontSize = 13.sp) }
                OutlinedButton(
                    onClick = { viewModel.stopAgent() },
                    enabled = state.agentState is AgentState.Running,
                ) { Text("Stop", fontSize = 13.sp) }
            }
        }

        SectionCard("Model runtime") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(
                    when (state.llmState) {
                        is ServerState.Running -> Positive
                        is ServerState.Failed -> Negative
                        is ServerState.Starting -> TextSecondary
                        ServerState.Idle -> TextFaint
                    }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when (val s = state.llmState) {
                        is ServerState.Running -> "running · ${s.model}"
                        is ServerState.Failed -> s.reason
                        is ServerState.Starting -> "loading ${s.model}…"
                        ServerState.Idle -> "stopped"
                    },
                    color = TextPrimary,
                    fontSize = 14.sp,
                )
            }

            state.activeModelFile?.let { KeyValue("Model file", it.name) }
            KeyValue("OpenAI-compatible", "http://127.0.0.1:${LlmServer.PORT}/v1")
            KeyValue("Ollama facade", "http://127.0.0.1:${OllamaFacade.PORT}")

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { viewModel.startLlm() },
                    enabled = state.activeModelFile != null && state.llmState !is ServerState.Running,
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceHigh, contentColor = TextPrimary),
                ) { Text("Start runtime", fontSize = 13.sp) }
                OutlinedButton(
                    onClick = { viewModel.stopLlm() },
                    enabled = state.llmState is ServerState.Running,
                ) { Text("Stop", fontSize = 13.sp) }
            }

            if (state.modelsJson.isNotBlank()) {
                Text("/v1/models", color = TextFaint, fontSize = 11.sp)
                Mono(state.modelsJson, maxLines = 12)
            }
        }

        if (state.availableModels.isNotEmpty()) {
            SectionCard("Models the agent can see") {
                state.availableModels.take(20).forEach {
                    KeyValue(it.providerID, it.modelID)
                }
            }
        }

        if (state.log.isNotEmpty()) {
            SectionCard("Log") {
                state.log.take(12).forEach {
                    Text(it, color = TextFaint, fontSize = 11.sp)
                }
            }
        }
    }
}