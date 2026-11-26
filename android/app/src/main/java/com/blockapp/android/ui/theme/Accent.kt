package com.blockapp.android.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * The accent in force. One flat colour each, with its own ink for text on a fill and a wash for
 * quiet containers: still no gradients and no glows (Color.kt). None of them is orange, because
 * Ember belongs to the reel counter.
 *
 * Keel 2 picks accents in the shared UI (unlocked by level, ui/src/screens/accent.ts); the few
 * native screens left (setup, the wall, Remove protection) stay on Lime.
 */
enum class AccentColor(
    val id: String,
    val label: String,
    val main: Color,
    val ink: Color,
    val wash: Color,
) {
    LIME("lime", "Lime", Color(0xFFC6F432), Color(0xFF141A00), Color(0xFF263015)),
}

/**
 * The accent as Compose state. Color.kt's Lime/LimeInk/LimeWash read through here, which is why
 * they are getters; kept so a future synced accent recolours every screen without rewriting the
 * ~50 places that name Lime directly.
 */
object AccentState {
    var current by mutableStateOf(AccentColor.LIME)
        private set

    @Suppress("UNUSED_PARAMETER")
    fun ensureLoaded(context: Context) = Unit
}
