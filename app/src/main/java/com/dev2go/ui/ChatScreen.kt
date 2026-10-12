package com.dev2go.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dev2go.agent.AgentState
import com.dev2go.core.ContentPart
import com.dev2go.core.Message
import com.dev2go.core.ModelRef
import com.dev2go.ui.theme.OpenChamber as OC

/**
 * The chat pane, built to OpenChamber's anatomy rather than a generic messenger.
 *
 * The details that carry the look: user turns sit on their own warm background
 * (#2d1c12) while assistant turns sit directly on the page; assistant output is plain body
 * text with no bubble at all; tool calls are bordered cards with a muted icon, not inline
 * text; and turns are separated by hairlines rather than spacing alone.
 */
@Composable
fun ChatScreen(viewModel: AppViewModel, state: UiState) {
    val listState = rememberLazyListState()
    var expandedTool by remember { mutableStateOf<String?>(null) }
    var showPalette by remember { mutableStateOf(false) }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Box(Modifier.fillMaxSize().background(OC.ChatBackground)) {
        Column(Modifier.fillMaxSize()) {
            if (state.agentState !is AgentState.Running) {
                AgentBanner(state.agentState, viewModel)
            }

            // Session strip: a scrollable row of sessions, the way OpenChamber keeps recent
            // work one tap away without a sidebar eating phone width.
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LazyRow(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(state.sessions, key = { it.id }) { session ->
                        SessionPill(session.displayTitle, session.id == state.activeSessionId) {
                            viewModel.openSession(session.id)
                        }
                    }
                }
                Box(
                    Modifier
                        .padding(horizontal = 6.dp)
                        .border(1.dp, OC.Border, RoundedCornerShape(8.dp))
                        .clickable { viewModel.newSession() }
                        .padding(7.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New session", tint = OC.ForegroundMuted, modifier = Modifier.size(16.dp))
                }
            }

            Box(Modifier.height(1.dp).fillMaxWidth().background(OC.ChatDivider))

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                items(state.messages, key = { it.id }) { message ->
                    MessageView(
                        message,
                        expandedTool = expandedTool,
                        onToggleTool = { key -> expandedTool = if (expandedTool == key) null else key },
                    )
                    Box(Modifier.fillMaxWidth().height(1.dp).background(OC.ChatDivider))
                }

                if (state.sending) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StatusDot(OC.Primary, Modifier.size(6.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(state.streamingNote ?: "working…", color = OC.ForegroundMuted, fontSize = 13.sp)
                        }
                    }
                }
            }

            if (state.availableModels.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(OC.ChatDivider))
                LazyRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.availableModels, key = { it.toString() }) { model ->
                        ModelChip(model, model == state.selectedModel) { viewModel.selectModel(model) }
                    }
                }
            }

            Composer(state, viewModel, onPalette = { showPalette = true })
        }

        if (showPalette) {
            CommandPalette(state, viewModel) { showPalette = false }
        }
    }
}

@Composable
private fun AgentBanner(agentState: AgentState, viewModel: AppViewModel) {
    val text = when (agentState) {
        is AgentState.Absent -> "Agent not running — start it on the Runtime tab."
        is AgentState.Starting -> "Starting opencode…"
        is AgentState.Failed -> agentState.reason
        is AgentState.Running -> ""
    }
    if (text.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().background(OC.SurfaceMuted).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(if (agentState is AgentState.Failed) OC.Error else OC.Warning, Modifier.size(6.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            color = if (agentState is AgentState.Failed) OC.Error else OC.ForegroundMuted,
            fontSize = 12.5.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            "Runtime",
            color = OC.Primary,
            fontSize = 12.5.sp,
            modifier = Modifier.clickable { viewModel.select(Tab.Runtime) },
        )
    }
}

@Composable
private fun SessionPill(title: String, active: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (active) OC.SurfaceElevated else Color.Transparent,
        tween(OC.TransitionNormal),
        label = "session-bg",
    )
    Box(
        Modifier
            .background(bg, RoundedCornerShape(7.dp))
            .then(if (active) Modifier.border(1.dp, OC.ChatDivider, RoundedCornerShape(7.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            title.take(26),
            color = if (active) OC.Foreground else OC.ForegroundMuted,
            fontSize = 12.5.sp,
            maxLines = 1,
        )
    }
}

/**
 * One turn.
 *
 * User turns get a background and read right-aligned in spirit; assistant turns get no
 * container at all and sit on the page. Bubbling both makes a transcript look like a
 * messenger rather than a transcript, which is not what OpenChamber is.
 */
@Composable
private fun MessageView(
    message: Message,
    expandedTool: String?,
    onToggleTool: (String) -> Unit,
) {
    if (message.isUser) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(OC.ChatUserBackground)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            message.text?.let {
                Text(it, color = OC.Foreground, fontSize = 14.5.sp, lineHeight = 21.sp)
            }
        }
        return
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                message.agent ?: "assistant",
                color = OC.ForegroundMuted,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
            )
            message.model?.let {
                Spacer(Modifier.width(8.dp))
                Text(it.modelID, color = OC.ForegroundMuted, fontSize = 10.5.sp, fontFamily = MonoFont)
            }
        }

        Spacer(Modifier.height(6.dp))

        message.text?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = OC.Foreground, fontSize = 14.5.sp, lineHeight = 21.sp)
        }

        // Tool calls and reasoning are content parts, not message text. Rendering only
        // `text` leaves a turn that spent itself calling tools looking empty.
        message.content?.forEach { part -> ContentPartView(part, expandedTool, onToggleTool) }

        if (message.failed) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(OC.ToolRemovedBackground, RoundedCornerShape(8.dp))
                    .border(1.dp, OC.Error.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(OC.Error, Modifier.size(6.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    message.error?.display ?: "turn failed",
                    color = OC.Error,
                    fontSize = 12.5.sp,
                )
            }
        }
    }
}

@Composable
private fun ContentPartView(part: ContentPart, expandedTool: String?, onToggle: (String) -> Unit) {
    when (part.type) {
        "text" -> part.text?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = OC.Foreground, fontSize = 14.5.sp, lineHeight = 21.sp)
        }

        "reasoning" -> part.text?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                it,
                color = OC.ForegroundMuted,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, OC.ChatDivider, RoundedCornerShape(8.dp))
                    .padding(10.dp),
            )
        }

        "tool" -> {
            val key = part.tool.orEmpty()
            val expanded = expandedTool == key
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, OC.ToolBorder, RoundedCornerShape(8.dp))
                    .clickable { onToggle(key) }
                    .padding(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(OC.Success, Modifier.size(6.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(key, color = OC.ToolTitle, fontSize = 12.5.sp, fontFamily = MonoFont)
                    Spacer(Modifier.weight(1f))
                    Text(if (expanded) "−" else "+", color = OC.ToolIcon, fontSize = 13.sp)
                }
                if (expanded) {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(OC.ChatDivider))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        part.state?.toString()?.take(1200).orEmpty(),
                        color = OC.ToolDescription,
                        fontSize = 11.sp,
                        fontFamily = MonoFont,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelChip(model: ModelRef, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (selected) OC.SurfaceElevated else Color.Transparent, RoundedCornerShape(6.dp))
            .border(1.dp, if (selected) OC.BorderHover else OC.ChatDivider, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(
            model.modelID,
            color = if (selected) OC.Foreground else OC.ForegroundMuted,
            fontSize = 11.5.sp,
            fontFamily = MonoFont,
        )
    }
}

@Composable
private fun Composer(state: UiState, viewModel: AppViewModel, onPalette: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(OC.Surface)
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            Modifier
                .border(1.dp, if (state.agentState is AgentState.Running) OC.Border else OC.ChatDivider, RoundedCornerShape(10.dp))
                .clickable { onPalette() }
                .padding(horizontal = 10.dp, vertical = 12.dp),
        ) {
            Text("/", color = OC.ForegroundMuted, fontSize = 16.sp, fontFamily = MonoFont)
        }
        Spacer(Modifier.width(8.dp))

        OutlinedTextField(
            value = state.composer,
            onValueChange = viewModel::onComposerChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    if (state.agentState is AgentState.Running) "Message the agent…"
                    else "Agent is stopped",
                    color = OC.ForegroundMuted,
                    fontSize = 14.sp,
                )
            },
            textStyle = androidx.compose.ui.text.TextStyle(color = OC.Foreground, fontSize = 14.5.sp),
            maxLines = 6,
            shape = RoundedCornerShape(10.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = OC.FocusRing,
                unfocusedBorderColor = OC.Border,
                focusedContainerColor = OC.SurfaceMuted,
                unfocusedContainerColor = OC.SurfaceMuted,
                cursorColor = OC.Primary,
            ),
        )

        Spacer(Modifier.width(8.dp))
        val agentUp = state.agentState is AgentState.Running
        val canSend = agentUp && state.composer.isNotBlank()
        Box(
            Modifier
                .background(
                    when {
                        state.sending -> OC.Error
                        canSend -> OC.Primary
                        else -> OC.SurfaceSubtle
                    },
                    RoundedCornerShape(50),
                )
                .clickable(enabled = state.sending || canSend) {
                    if (state.sending) viewModel.stopTurn() else viewModel.send()
                }
                .padding(9.dp),
        ) {
            Icon(
                if (state.sending) Icons.Default.Stop else Icons.Default.Send,
                contentDescription = if (state.sending) "Stop" else "Send",
                tint = if (state.sending || canSend) OC.Surface else OC.ForegroundMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}