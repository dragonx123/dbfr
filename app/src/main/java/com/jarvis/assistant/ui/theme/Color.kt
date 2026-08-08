package com.jarvis.assistant.ui.theme

import androidx.compose.ui.graphics.Color

val JarvisAccent = Color(0xFF00D1FF)
val JarvisAccentDim = Color(0xFF0090B0)

// True black, not the navy-grey the app started with — sleeker, and matches
// VoiceModeScreen's already-black canvas exactly instead of clashing with it.
val JarvisBackground = Color(0xFF000000)

// Panel fills (cards, the AI's chat bubble) sit just barely above pure
// black — never a lit-up grey box. Depth comes from a thin JarvisOutline
// edge instead of a lighter fill, so panels read as "HUD glass," not grey.
val JarvisSurface = Color(0xFF0A0D10)
val JarvisSurfaceVariant = Color(0xFF0E1317)

// Cyan-tinted panel edge used as a thin border on cards/bubbles — the same
// "circuit trace" language as the voice orb, carried into the rest of the UI.
val JarvisOutline = Color(0xFF1C2932)

val JarvisOnBackground = Color(0xFFE6F1F5)
val JarvisOnSurfaceMuted = Color(0xFF7C8D96)
val JarvisError = Color(0xFFFF5C5C)
val JarvisWarn = Color(0xFFFFC94A)
