package org.pockettts.android.engine

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.format.Formatter
import android.view.View
import android.view.animation.PathInterpolator
import java.io.File
import java.util.Locale
import kotlin.math.abs

/** An installed voice together with its language pack. */
internal data class VoiceEntry(val pack: ModelPack, val voice: PackVoice) {
    val name: String get() = ModelPackRepository.voiceName(pack, voice)
    val initial: String get() = voice.displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}

/** Small shared helpers for the brand UI (voices, avatars, formatting, motion). */
internal object UiKit {
    val emphasized = PathInterpolator(0.2f, 0f, 0f, 1f)

    private val avatarPalettes = listOf(
        intArrayOf(0xFF0A2A5E.toInt(), 0xFF4FA3FF.toInt()),
        intArrayOf(0xFF1F55C4.toInt(), 0xFF7DC0FF.toInt()),
        intArrayOf(0xFF0E3A7A.toInt(), 0xFF3B82F6.toInt()),
        intArrayOf(0xFF123B6B.toInt(), 0xFF38BDF8.toInt()),
        intArrayOf(0xFF1E3A8A.toInt(), 0xFF60A5FA.toInt()),
        intArrayOf(0xFF0B2447.toInt(), 0xFF2563EB.toInt()),
        intArrayOf(0xFF164E8C.toInt(), 0xFF93C5FD.toInt()),
        intArrayOf(0xFF0F2F57.toInt(), 0xFF0EA5E9.toInt())
    )

    fun dp(context: Context, value: Float): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun allVoices(context: Context): List<VoiceEntry> =
        ModelPackRepository.list(context).flatMap { pack -> pack.voices.map { VoiceEntry(pack, it) } }

    fun selectedVoice(context: Context): VoiceEntry? {
        val pack = ModelPackRepository.selectedPack(context) ?: return null
        val id = ModelPackRepository.selectedVoiceId(context, pack) ?: return null
        return pack.voices.firstOrNull { it.id == id }?.let { VoiceEntry(pack, it) }
    }

    /** Persists the selection exactly like the former pack and voice spinners did. */
    fun select(context: Context, entry: VoiceEntry) {
        ModelPackRepository.selectPack(context, entry.pack.id)
        ModelPackRepository.selectVoice(context, entry.pack.id, entry.voice.id)
    }

    fun isFastPack(pack: ModelPack): Boolean =
        pack.precision == "int8" || pack.id.endsWith("-q4") || pack.id.endsWith("-int8")

    fun qualityLabel(context: Context, pack: ModelPack): String =
        context.getString(if (isFastPack(pack)) R.string.badge_fast else R.string.badge_hq)

    fun languageLabel(pack: ModelPack): String {
        val display = Locale.getDefault()
        val name = pack.locale.getDisplayLanguage(display).ifBlank { pack.languageTag }
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(display) else it.toString() }
    }

    fun languageCode(pack: ModelPack): String = pack.locale.language.uppercase(Locale.ROOT).take(3)

    fun avatar(seed: String): GradientDrawable = gradient(seed).apply { shape = GradientDrawable.OVAL }

    fun tile(seed: String, radiusPx: Float): GradientDrawable = gradient(seed).apply { cornerRadius = radiusPx }

    private fun gradient(seed: String): GradientDrawable {
        val colors = avatarPalettes[abs(seed.hashCode() % avatarPalettes.size)]
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
    }

    fun pill(fill: Int, radiusPx: Float, stroke: Int = Color.TRANSPARENT, strokePx: Int = 0) = GradientDrawable().apply {
        cornerRadius = radiusPx
        setColor(fill)
        if (strokePx > 0) setStroke(strokePx, stroke)
    }

    fun dot(color: Int, sizePx: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setSize(sizePx, sizePx)
    }

    fun formatBytes(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    fun sizeOnDisk(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun formatDuration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    /** A context whose resources use [locale], for per-voice sample sentences. */
    fun localized(context: Context, locale: Locale): Context =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })

    fun fadeIn(view: View, duration: Long = 220) {
        if (view.visibility == View.VISIBLE && view.alpha == 1f) return
        view.alpha = 0f
        view.translationY = dp(view.context, 8f).toFloat()
        view.visibility = View.VISIBLE
        view.animate().alpha(1f).translationY(0f).setDuration(duration).setInterpolator(emphasized).start()
    }
}
