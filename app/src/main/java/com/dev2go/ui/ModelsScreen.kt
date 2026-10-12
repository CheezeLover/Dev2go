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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dev2go.models.CatalogEntry
import com.dev2go.ui.theme.Accent
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
 * The model pane: browse the Ollama library, download a GGUF, see what is installed.
 *
 * The catalog comes from registry.ollama.ai over plain HTTP — an OCI registry — so it works
 * with no Ollama installed, which matters because there is no official Ollama build for
 * Android to talk to.
 */
@Composable
fun ModelsScreen(viewModel: AppViewModel, state: UiState, onPickFile: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().background(Ground).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Models",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onPickFile) { Text("Import GGUF", fontSize = 12.sp) }
            }
        }

        if (state.download != null) {
            item {
                val progress = state.download
                SectionCard("Downloading ${progress.reference}") {
                    Text(
                        progress.bytesRead.humanBytes() + " / " + progress.totalBytes.humanBytes(),
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progress.fraction ?: 0f },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = Accent,
                    )
                }
            }
        }

        if (state.installed.isNotEmpty()) {
            item { Text("ON DEVICE", color = TextFaint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
            items(state.installed, key = { it.name }) { file ->
                val active = file.name == state.activeModelFile?.name
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (active) SurfaceHigh else Surface, RoundedCornerShape(12.dp))
                        .clickable { viewModel.selectInstalled(file) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(file.name, color = TextPrimary, fontSize = 13.sp)
                        Text(
                            file.length().humanBytes() + if (active) "  ·  selected" else "",
                            color = if (active) Positive else TextFaint,
                            fontSize = 11.sp,
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.selectInstalled(file) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (active) Positive else TextSecondary),
                    ) { Text(if (active) "Use" else "Select", fontSize = 11.sp) }
                }
            }
        }

        item { Text("OLLAMA LIBRARY", color = TextFaint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.library, key = { it }) { model ->
                    val selected = model == state.chosenModel
                    Box(
                        Modifier
                            .background(if (selected) Outline else Surface, RoundedCornerShape(50))
                            .clickable { viewModel.chooseLibraryModel(model) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(model, color = if (selected) TextPrimary else TextFaint, fontSize = 12.sp)
                    }
                }
            }
        }

        if (state.catalogLoading) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Reading the registry…", color = TextFaint, fontSize = 12.sp)
                }
            }
        }

        items(state.catalog, key = { it.reference.toString() }) { entry ->
            CatalogRow(entry, state, viewModel)
        }

        if (!state.catalogLoading && state.catalog.isEmpty()) {
            item {
                Text(
                    "No tags listed. The library index may be unreachable; imported models still work.",
                    color = TextFaint,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun CatalogRow(entry: CatalogEntry, state: UiState, viewModel: AppViewModel) {
    val downloading = state.download?.reference == entry.reference
    Column(
        Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.displayName,
                color = TextPrimary,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Pill(if (entry.supportsTools) "tools" else "chat", if (entry.supportsTools) Positive else TextFaint)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(entry.sizeLabel, color = TextSecondary, fontSize = 12.sp)
            entry.quantization.takeIf { it.isNotBlank() }?.let {
                Text(it, color = TextFaint, fontSize = 12.sp)
            }
        }

        // Say plainly why a model is a poor fit, rather than letting the user find out
        // after a 4 GB download that it cannot call tools.
        Text(
            entry.phoneNote,
            color = if (entry.supportsTools) TextFaint else Negative,
            fontSize = 11.sp,
        )

        Button(
            onClick = { viewModel.download(entry) },
            enabled = !downloading && state.download == null,
            colors = ButtonDefaults.buttonColors(containerColor = SurfaceHigh, contentColor = TextPrimary),
        ) {
            Text(if (downloading) "Downloading…" else "Download", fontSize = 12.sp)
        }
    }
}

private fun Long.humanBytes(): String = when {
    this >= 1_000_000_000 -> "%.2f GB".format(this / 1_000_000_000.0)
    this >= 1_000_000 -> "%d MB".format(this / 1_000_000)
    this >= 1_000 -> "%d kB".format(this / 1_000)
    else -> "$this B"
}