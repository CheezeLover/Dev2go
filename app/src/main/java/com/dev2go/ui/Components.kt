package com.dev2go.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@Composable
fun SectionCard(title: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceHigh, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null) {
            Text(
                title.uppercase(),
                color = TextFaint,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
        }
        content()
    }
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = TextSecondary) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(key, color = TextFaint, fontSize = 12.sp, modifier = Modifier.width(112.dp))
        Text(value, color = valueColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Spacer(modifier.background(color, RoundedCornerShape(50)).size(8.dp))
}

@Composable
fun Pill(text: String, color: Color) {
    Box(
        Modifier
            .border(1.dp, Outline, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, color = color, fontSize = 11.sp)
    }
}

@Composable
fun Mono(text: String, color: Color = TextSecondary, maxLines: Int = 20) {
    Text(text, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = maxLines)
}

/** Shared colours used by several screens, so state means the same thing everywhere. */
object Status {
    val running = Positive
    val stopped = TextFaint
    val broken = Negative
    val active = Accent
    val idle = Ground
}