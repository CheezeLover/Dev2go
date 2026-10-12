package com.dev2go.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dev2go.ui.theme.OpenChamber as OC

/**
 * The two font families OpenChamber uses.
 *
 * The mono family is `MonoFont` rather than `Mono` because this file also has a
 * @Composable `Mono(...)` that renders a code block, and two declarations of the same name
 * in one package resolves against the wrong one.
 */
val SansFont = FontFamily.SansSerif
val MonoFont = FontFamily.Monospace

@Composable
fun SectionCard(title: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OC.SurfaceElevated, RoundedCornerShape(12.dp))
            .border(1.dp, OC.ChatDivider, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null) {
            Text(
                title.uppercase(),
                color = OC.ForegroundMuted,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
        }
        content()
    }
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = OC.ForegroundMuted) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(key, color = OC.ForegroundMuted, fontSize = 12.sp, modifier = Modifier.width(104.dp))
        Text(value, color = valueColor, fontSize = 12.sp, fontFamily = MonoFont)
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Spacer(modifier.background(color, RoundedCornerShape(50)).size(8.dp))
}

@Composable
fun Pill(text: String, color: Color, filled: Boolean = false) {
    Box(
        Modifier
            .background(if (filled) color else Color.Transparent, RoundedCornerShape(50))
            .border(1.dp, color, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text,
            color = if (filled) OC.Surface else color,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Section heading used inside panes — small, muted, tracked out. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = OC.ForegroundMuted,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = modifier,
    )
}

/**
 * A fenced code block, coloured with OpenChamber's Vitesse syntax palette.
 *
 * The highlighter is deliberately shallow: it recognises comments, strings, numbers,
 * keywords and types by pattern. A phone has no room for a real parser, and the thing that
 * makes code readable here is that comments recede and keywords advance — which this does.
 */
@Composable
fun CodeBlock(code: String, language: String? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(OC.SyntaxBackground, RoundedCornerShape(8.dp))
            .border(1.dp, OC.ChatDivider, RoundedCornerShape(8.dp)),
    ) {
        if (language != null) {
            Text(
                language,
                color = OC.ForegroundMuted,
                fontSize = 10.5.sp,
                fontFamily = MonoFont,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp),
            )
        }
        Text(
            highlight(code),
            color = OC.Foreground,
            fontSize = 11.5.sp,
            fontFamily = MonoFont,
            lineHeight = 17.sp,
            modifier = Modifier.padding(12.dp),
        )
    }
}

private val KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "interface", "return", "if", "else", "when",
    "for", "while", "do", "try", "catch", "finally", "throw", "import", "package", "const",
    "let", "function", "def", "async", "await", "new", "this", "super", "extends",
    "implements", "public", "private", "protected", "static", "final", "type", "struct",
    "enum", "match", "case", "switch", "break", "continue", "true", "false", "null", "nil",
)

private val TYPES = setOf(
    "string", "number", "boolean", "int", "float", "double", "long", "void", "any",
    "String", "Int", "Boolean", "List", "Map", "Set", "Array", "Object", "Promise",
)

/**
 * Applies syntax colours by pattern.
 *
 * String and comment detection is order-sensitive: a `//` inside a string must not turn the
 * rest of the line green, so strings are matched first and comments only in what remains.
 */
private fun highlight(code: String) = buildAnnotatedString {
    code.lines().forEachIndexed { index, line ->
        if (index > 0) append('\n')
        var i = 0
        while (i < line.length) {
            val rest = line.substring(i)
            when {
                rest.startsWith("//") || rest.startsWith("#") ->
                    withStyle(SpanStyle(color = OC.SyntaxComment)) { append(rest) }.also { i = line.length }

                rest.startsWith("/*") -> {
                    val end = line.indexOf("*/", i).let { if (it < 0) line.length else it + 2 }
                    withStyle(SpanStyle(color = OC.SyntaxComment)) { append(line.substring(i, end)) }
                    i = end
                }

                rest.startsWith("\"") || rest.startsWith("'") -> {
                    val quote = rest.first()
                    var j = 1
                    while (j < rest.length && rest[j] != quote) j += if (rest[j] == '\\') 2 else 1
                    val end = minOf(line.length, i + j + 1)
                    withStyle(SpanStyle(color = OC.SyntaxString)) { append(line.substring(i, end)) }
                    i = end
                }

                else -> {
                    val word = rest.takeWhile { it.isLetterOrDigit() || it == '_' }
                    if (word.isEmpty()) {
                        val c = rest.first()
                        val color = when {
                            c.isDigit() -> OC.SyntaxNumber
                            c in "+-*/%=<>!&|^~?:".toSet() -> OC.SyntaxOperator
                            else -> OC.SyntaxPunctuation
                        }
                        withStyle(SpanStyle(color = color)) { append(c) }
                        i++
                    } else {
                        val style = when {
                            word in KEYWORDS -> SpanStyle(color = OC.SyntaxKeyword, fontWeight = FontWeight.Medium)
                            word in TYPES || word.firstOrNull()?.isUpperCase() == true ->
                                SpanStyle(color = OC.SyntaxType)
                            else -> SpanStyle(color = OC.Foreground)
                        }
                        withStyle(style) { append(word) }
                        i += word.length
                    }
                }
            }
        }
    }
}

/** One line of a unified diff, coloured by disposition. */
@Composable
fun DiffLine(prefix: String, text: String) {
    val (bg, fg) = when (prefix) {
        "+" -> OC.ToolAddedBackground to OC.DiffAdded
        "-" -> OC.ToolRemovedBackground to OC.DiffRemoved
        else -> Color.Transparent to OC.Foreground
    }
    Row(
        Modifier.fillMaxWidth().background(bg).padding(horizontal = 10.dp, vertical = 1.dp),
    ) {
        Text(prefix, color = fg, fontSize = 11.sp, fontFamily = MonoFont, modifier = Modifier.width(12.dp))
        Text(text, color = fg, fontSize = 11.sp, fontFamily = MonoFont)
    }
}

/** Inline code, the way OpenChamber styles it inside prose. */
@Composable
fun InlineCode(text: String) {
    Box(
        Modifier
            .background(OC.MarkdownInlineCodeBackground, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(text, color = OC.MarkdownInlineCode, fontSize = 12.5.sp, fontFamily = MonoFont)
    }
}