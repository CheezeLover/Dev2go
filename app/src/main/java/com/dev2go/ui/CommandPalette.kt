package com.dev2go.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dev2go.ui.theme.OpenChamber as OC

/**
 * The command palette, reached with `/` in the composer.
 *
 * OpenChamber leads its chat surface with a command affordance rather than a toolbar, and
 * on a phone a filtered list beats a nested menu: every action is one list row away and the
 * search does the finding.
 */
@Composable
fun CommandPalette(state: UiState, viewModel: AppViewModel, onDismiss: () -> Unit) {
    val commands = rememberCommands(state, viewModel)

    Box(
        Modifier
            .fillMaxSize()
            .background(OC.Surface.copy(alpha = 0.72f))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .background(OC.SurfaceElevated, RoundedCornerShape(12.dp))
                .border(1.dp, OC.BorderHover, RoundedCornerShape(12.dp))
                .clickable(enabled = false) {},
        ) {
            Text(
                "COMMANDS",
                color = OC.ForegroundMuted,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 8.dp),
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(OC.ChatDivider))

            if (commands.isEmpty()) {
                Text(
                    "Nothing available yet — start the agent first.",
                    color = OC.ForegroundMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp),
                )
            }

            LazyColumn {
                items(commands, key = { it.label }) { command ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                command.run()
                                onDismiss()
                            }
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusDot(command.tint, Modifier.size(6.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(command.label, color = OC.Foreground, fontSize = 14.sp)
                            if (command.detail != null) {
                                Text(command.detail, color = OC.ForegroundMuted, fontSize = 11.5.sp)
                            }
                        }
                        Text("↵", color = OC.ForegroundMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

private data class Command(val label: String, val detail: String?, val tint: androidx.compose.ui.graphics.Color, val run: () -> Unit)

@Composable
private fun rememberCommands(state: UiState, viewModel: AppViewModel): List<Command> {
    val agentUp = state.agentState is com.dev2go.agent.AgentState.Running
    val modelUp = state.llmState is com.dev2go.runtime.ServerState.Running
    return buildList {
        add(Command("New session", "Start a fresh conversation", OC.Primary) { viewModel.newSession() })
        add(Command("Refresh sessions", "Re-read from the agent", OC.Info) { viewModel.refreshSessions() })
        add(
            Command(
                if (agentUp) "Stop agent" else "Start agent",
                if (agentUp) "Shut the opencode process down" else "Run the bundled opencode binary",
                if (agentUp) OC.Warning else OC.Success,
            ) { if (agentUp) viewModel.stopAgent() else viewModel.startAgent() },
        )
        add(
            Command(
                if (modelUp) "Stop model runtime" else "Start model runtime",
                state.activeModelFile?.name ?: "No GGUF selected",
                if (modelUp) OC.Warning else OC.Success,
            ) { if (modelUp) viewModel.stopLlm() else viewModel.startLlm() },
        )
        add(Command("Models", "Browse the Ollama library", OC.Primary) { viewModel.select(Tab.Models) })
        add(Command("Runtime", "Agent, runtime and facade status", OC.ForegroundMuted) { viewModel.select(Tab.Runtime) })
        if (state.activeSessionId != null && state.sending) {
            add(Command("Interrupt", "Stop the current turn", OC.Error) { viewModel.stopTurn() })
        }
    }
}