package com.blockapp.android.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.blockapp.android.util.Rewards

/**
 * The accent the user picked (Lime unless a reward unlocked another). One flat colour each, with
 * its own ink for text on a fill and a wash for quiet containers: still no gradients and no
 * glows (Color.kt). None of them is orange, because Ember belongs to the reel counter.
 *
 * Every accent but Lime is earned through util/Rewards; [rewardId] names the reward.
 */
enum class AccentColor(
    val id: String,
    val label: String,
    val main: Color,
    val ink: Color,
    val wash: Color,
    val rewardId: String?,
) {
    LIME("lime", "Lime", Color(0xFFC6F432), Color(0xFF141A00), Color(0xFF263015), null),
    MINT("mint", "Mint", Color(0xFF5CE6B0), Color(0xFF00281A), Color(0xFF16302A), Rewards.ACCENT_MINT),
    SKY("sky", "Sky", Color(0xFF7CC4FF), Color(0xFF00213A), Color(0xFF17283A), Rewards.ACCENT_SKY),
    LILAC("lilac", "Lilac", Color(0xFFBFA3FF), Color(0xFF1E0E45), Color(0xFF28223D), Rewards.ACCENT_LILAC),
    ROSE("rose", "Rose", Color(0xFFFF8DB4), Color(0xFF3D0019), Color(0xFF3A1D28), Rewards.ACCENT_ROSE),
    GOLD("gold", "Gold", Color(0xFFFFD15C), Color(0xFF2E2000), Color(0xFF362D14), Rewards.ACCENT_GOLD),
    MONO("mono", "Mono", Color(0xFFE8E9E3), Color(0xFF0E0F0C), Color(0xFF2B2D28), Rewards.SECRET_MONO),
    ;

    companion object {
        fun fromId(id: String?): AccentColor = entries.firstOrNull { it.id == id } ?: LIME
    }
}

/**
 * The accent in force, as Compose state, so changing it recolours every screen at once with no
 * restart. Color.kt's Lime/LimeInk/LimeWash read through here, which is why they are getters:
 * the ~50 places that name Lime directly follow the pick without each being rewritten.
 * Persisted through Rewards (SharedPreferences); [ensureLoaded] reads it once per process.
 */
object AccentState {
    var current by mutableStateOf(AccentColor.LIME)
        private set

    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        current = AccentColor.fromId(Rewards.accentId(context))
    }

    /** Applies [accent] now and stores it. Refuses one that isn't unlocked. */
    fun choose(context: Context, accent: AccentColor): Boolean {
        if (accent.rewardId != null && !Rewards.isEarned(context, accent.rewardId)) return false
        Rewards.setAccentId(context, accent.id)
        current = accent
        loaded = true
        return true
    }
}
