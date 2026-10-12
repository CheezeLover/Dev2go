package com.dev2go.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * OpenChamber's actual design tokens, not an approximation.
 *
 * Taken from `@openchamber/web@2.3.0`, the `openchamber-dark` palette defined in
 * `dist/assets/useAppFontEffects-*.js`. Its own description reads: *"The OpenChamber
 * signature — Vitesse syntax over OpenCode-deep neutrals with Flexoki accents, harmonized
 * in OKLCH (dark variant)"*.
 *
 * The palette is **warm**. Ground is a brown-black (#120f0e), not a blue-black, and the
 * primary is a terracotta orange (#da7c47), not a blue. An earlier version of this file
 * used a cold near-black with a blue accent and looked like a different product
 * entirely — which is what it was.
 *
 * Values are copied verbatim. Anything invented here is the thing that makes a UI read as
 * "not that tool".
 */
object OpenChamber {

    // ---- primary: Flexoki orange ----
    val Primary = Color(0xFFDA7C47)
    val PrimaryHover = Color(0xFFEB8C57)
    val PrimaryActive = Color(0xFFFD9B66)
    val PrimaryMuted = Color(0x80DA7C47)

    // ---- surface: OpenCode-deep warm neutrals ----
    val Surface = Color(0xFF120F0E)
    val Foreground = Color(0xFFC9C5BA)
    val SurfaceMuted = Color(0xFF0E0C0B)
    val ForegroundMuted = Color(0xFF8F8B81)
    val SurfaceElevated = Color(0xFF1B1918)
    val SurfaceSubtle = Color(0xFF171616)

    // ---- interactive ----
    val Border = Color(0xBF242323)
    val Selection = Color(0x2BC8C6C5)
    val BorderHover = Color(0xFF504E4C)
    val FocusRing = Color(0x55DA7C47)
    val Hover = Color(0x0FFFFFFF)
    val ActiveOverlay = Color(0x1AFFFFFF)

    // ---- status ----
    val Error = Color(0xFFDA5B4A)
    val Warning = Color(0xFFC67F13)
    val Success = Color(0xFF76AD4F)
    val Info = Color(0xFF479FE6)

    // ---- pull request states ----
    val PrOpen = Color(0xFF93A233)
    val PrBlocked = Color(0xFFDA7C47)
    val PrMerged = Color(0xFF9988E5)

    // ---- chat ----
    /** User messages get their own warm background. This is a signature detail. */
    val ChatUserBackground = Color(0xFF2D1C12)
    val ChatDivider = Color(0xFF302E2B)
    val ChatBackground = Color(0xFF120F0E)

    // ---- markdown ----
    val MarkdownLink = Color(0xFF5D99A9)
    val MarkdownLinkHover = Color(0xFF6BA7B8)
    val MarkdownInlineCode = Color(0xFF76AD4F)
    val MarkdownInlineCodeBackground = Color(0xFF100E0D)
    val MarkdownBlockquote = Color(0xFF8F8B81)
    val MarkdownBlockquoteBorder = Color(0xFF302E2B)
    val MarkdownListMarker = Color(0x994D934E)
    val MarkdownBold = Color(0xFFCECBC1)
    val MarkdownItalic = Color(0xFFC9C5BA)

    // ---- tools ----
    val ToolBorder = Color(0x73302E2B)
    val ToolIcon = Color(0xFF8F8B81)
    val ToolTitle = Color(0xFFC9C5BA)
    val ToolDescription = Color(0xFF8F8B81)
    val ToolAddedBackground = Color(0x254D934E)
    val ToolRemovedBackground = Color(0x25CB7676)
    val ToolLineNumber = Color(0xFF8F8B81)

    // ---- syntax (Vitesse) ----
    val SyntaxBackground = Color(0xFF100E0D)
    val SyntaxComment = Color(0xFF728772)
    val SyntaxKeyword = Color(0xFF34983A)
    val SyntaxString = Color(0xFFD58373)
    val SyntaxNumber = Color(0xFF279E93)
    val SyntaxFunction = Color(0xFF78A952)
    val SyntaxVariable = Color(0xFFC69457)
    val SyntaxType = Color(0xFF479CB1)
    val SyntaxOperator = Color(0xFFDA6B6D)
    val SyntaxPunctuation = Color(0xFF908B7E)
    val SyntaxClassName = Color(0xFF646FBA)
    val SyntaxProperty = Color(0xFFBDA94A)

    // ---- diff ----
    val DiffAdded = Color(0xFF4D934E)
    val DiffRemoved = Color(0xFFCB7676)
    val DiffModified = Color(0xFF5D99A9)

    // ---- motion ----
    const val TransitionFast = 150
    const val TransitionNormal = 250
    const val TransitionSlow = 350
}