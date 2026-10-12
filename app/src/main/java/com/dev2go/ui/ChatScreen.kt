package com.dev2go.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dev2go.agent.AgentState
import com.dev2go.core.Message
import com.dev2go.core.ModelRef
import com.dev2go.ui.theme.Ground
import com.dev2go.ui.theme.Negative
import com.dev2go.ui.theme.Outline
import com.dev2go.ui.theme.Positive
import com.dev2go.ui.theme.Surface
import com.dev2go.ui.theme.SurfaceHigh
import com.dev2go.ui.theme.TextFaint
import com.dev2go.ui.theme.TextPrimary
import com.dev2go.ui.theme.TextSecondary

/**
 * The chat pane: sessions, messages and the composer.
 *
 * Read-only while the agent is down rather than hiding the whole screen. A chat UI that
 * shows an empty page tells you nothing about why it is empty; showing the sessions with a
 * banner saying the agent is stopped tells you exactly what to do.
 */
@Composable
fun ChatScreen(viewModel: AppViewModel, state: UiState) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(Modifier.fillMaxSize().background(Ground)) {
        if (state.agentState !is AgentState.Running) {
            AgentBanner(state.agentState, viewModel)
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.sessions.firstOrNull { it.id == state.activeSessionId }?.displayTitle
                    ?: "No session",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { viewModel.newSession() }) {
                Icon(Icons.Default.Add, contentDescription = "New session", tint = TextSecondary)
            }
        }

        if (state.sessions.size > 1) {
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.sessions, key = { it.id }) { session ->
                    val active = session.id == state.activeSessionId
                    Box(
                        Modifier
                            .background(if (active) SurfaceHigh else Surface, RoundedCornerShape(50))
                            .clickable { viewModel.openSession(session.id) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            session.displayTitle.take(28),
                            color = if (active) TextPrimary else TextFaint,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
        ) {
            items(state.messages, key = { it.id }) { message ->
                MessageBubble(message)
            }
            if (state.sending) {
                item { Text(state.streamingNote ?: "working…", color = TextFaint, fontSize = 12.sp) }
            }
        }

        if (state.availableModels.isNotEmpty()) {
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.availableModels, key = { it.toString() }) { model ->
                    ModelChip(model, model == state.selectedModel) { viewModel.selectModel(model) }
                }
            }
        }

        Composer(state, viewModel)
    }
}

@Composable
private fun AgentBanner(agentState: AgentState, viewModel: AppViewModel) {
    val text = when (agentState) {
        is AgentState.Absent -> "Agent stopped — start it on the Runtime tab."
        is AgentState.Starting -> "Starting opencode…"
        is AgentState.Failed -> agentState.reason
        is AgentState.Running -> ""
    }
    if (text.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().background(SurfaceHigh).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = if (agentState is AgentState.Failed) Negative else TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { viewModel.select(Tab.Runtime) }) { Text("Runtime", fontSize = 12.sp) }
    }
}

@Composable
private fun MessageBubble(message: Message) {
    val failed = message.failed
    val background = when {
        message.isUser -> SurfaceHigh
        failed -> Color(0xFF2A1618)
        else -> Surface
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (message.isUser) "You" else message.agent ?: "assistant",
                color = if (message.isUser) TextSecondary else TextFaint,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            message.model?.let {
                Text(it.toString(), color = TextFaint, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }

        message.text?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.width(4.dp))
            Text(it, color = TextPrimary, fontSize = 14.sp)
        }

        // Tool calls and reasoning are content parts rather than message text. Rendering
        // only `text` would leave an assistant turn looking empty when the model spent the
        // turn calling tools.
        message.content?.forEach { part ->
            when (part.type) {
                "text" -> part.text?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = TextPrimary, fontSize = 14.sp)
                }
                "reasoning" -> part.text?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = TextFaint, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                "tool" -> Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(Positive, Modifier.size(6.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(part.tool ?: "tool", color = TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        if (failed) {
            Text(
                message.error?.display ?: "turn failed",
                color = Negative,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ModelChip(model: ModelRef, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (selected) Outline else Surface, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(model.modelID, color = if (selected) TextPrimary else TextFaint, fontSize = 11.sp)
    }
}

@Composable
private fun Composer(state: UiState, viewModel: AppViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Surface)
            .navigationBarsPadding()
            .imePadding()
            .padding(10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = state.composer,
            onValueChange = viewModel::onComposerChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message the agent…", color = TextFaint, fontSize = 14.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 14.sp),
            maxLines = 5,
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.width(8.dp))
        val enabled = state.agentState is AgentState.Running && state.composer.isNotBlank()
        IconButton(
            onClick = { if (state.sending) viewModel.stopTurn() else viewModel.send() },
            enabled = enabled || state.sending,
            modifier = Modifier.background(
                if (state.sending) Negative else Positive,
                RoundedCornerShape(50),
            ),
        ) {
            Icon(
                if (state.sending) Icons.Default.Stop else Icons.Default.Send,
                contentDescription = if (state.sending) "Stop" else "Send",
                tint = Ground,
            )
        }
    }
}