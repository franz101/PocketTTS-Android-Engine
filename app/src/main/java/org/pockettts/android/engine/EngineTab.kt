package org.pockettts.android.engine

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import com.google.android.material.slider.TickVisibilityMode
import java.util.Locale
import kotlin.math.roundToInt

/** The Engine tab: system status, the collapsible fine-tune card and about rows. */
internal class EngineTab(private val activity: MainActivity, private val root: View) {
    private val statusIcon: ImageView = root.findViewById(R.id.engine_status_icon)
    private val statusTitle: TextView = root.findViewById(R.id.engine_status_title)
    private val statusBody: TextView = root.findViewById(R.id.engine_status_body)
    private val voiceAvatar: TextView = root.findViewById(R.id.engine_voice_avatar)
    private val voiceName: TextView = root.findViewById(R.id.engine_voice_name)
    private val tuneBody: View = root.findViewById(R.id.finetune_body)
    private val tuneChevron: View = root.findViewById(R.id.finetune_chevron)
    private val tuneSubtitle: TextView = root.findViewById(R.id.finetune_subtitle)
    private val packGroup: ChipGroup = root.findViewById(R.id.finetune_packs)
    private val packLabel: View = root.findViewById(R.id.finetune_pack_label)
    private val tuneEmpty: View = root.findViewById(R.id.finetune_empty)
    private val saveButton: View = root.findViewById(R.id.save_button)
    private var packs: List<ModelPack> = emptyList()
    private var tunePack: ModelPack? = null
    private var expanded = false

    private val temperature = ParamControl(R.id.param_temperature, R.string.param_temperature,
        R.string.param_temperature_help, R.string.temperature_label, 0f, 2f, 0.01f) {
        String.format(Locale.getDefault(), "%.2f", it)
    }
    private val lsd = ParamControl(R.id.param_lsd, R.string.param_lsd_steps,
        R.string.param_lsd_steps_help, R.string.lsd_steps_label, 1f, 8f, 1f) { it.roundToInt().toString() }
    private val threads = ParamControl(R.id.param_threads, R.string.param_cpu_threads,
        R.string.param_cpu_threads_help, R.string.cpu_threads_label, 1f, 8f, 1f) { it.roundToInt().toString() }
    private val sentencePause = ParamControl(R.id.param_pause, R.string.param_sentence_pause,
        R.string.param_sentence_pause_help, R.string.sentence_pause_label, 0f, 2000f, 1f) {
        activity.getString(R.string.value_milliseconds, it.roundToInt())
    }
    private val maxTextTokens = ParamControl(R.id.param_tokens, R.string.param_max_text_tokens,
        R.string.param_max_text_tokens_help, R.string.max_text_tokens_label, 10f, 200f, 1f) { it.roundToInt().toString() }
    private val params = listOf(temperature, lsd, threads, sentencePause, maxTextTokens)

    init {
        root.findViewById<View>(R.id.open_tts_button).setOnClickListener { activity.openTtsSettings() }
        root.findViewById<View>(R.id.finetune_header).setOnClickListener { setExpanded(!expanded) }
        saveButton.setOnClickListener { save() }
        bindRow(R.id.row_about, R.drawable.ic_info, R.string.about_title,
            activity.getString(R.string.about_row_subtitle, BuildConfig.VERSION_NAME), external = false) { activity.showAbout() }
        bindRow(R.id.row_licenses, R.drawable.ic_file, R.string.licenses_title,
            activity.getString(R.string.licenses_subtitle), external = false) { activity.showLicenses() }
        bindRow(R.id.row_project, R.drawable.ic_code, R.string.project_title,
            activity.getString(R.string.project_subtitle), external = true) {
            activity.openUrl(MainActivity.REPOSITORY_URL, R.string.error_open_repository)
        }
        bindRow(R.id.row_packs, R.drawable.ic_download, R.string.download_language_packs,
            activity.getString(R.string.download_packs_subtitle), external = true) { activity.openPackDownloads() }
        root.findViewById<TextView>(R.id.engine_footer).text =
            activity.getString(R.string.engine_footer, BuildConfig.VERSION_NAME)
        packGroup.setOnCheckedStateChangeListener { _, ids ->
            val index = ids.firstOrNull()?.let { packGroup.findViewById<View>(it)?.tag as? Int } ?: return@setOnCheckedStateChangeListener
            val pack = packs.getOrNull(index) ?: return@setOnCheckedStateChangeListener
            if (pack.id != tunePack?.id) showParams(pack)
        }
    }

    private fun bindRow(id: Int, @DrawableRes icon: Int, @StringRes title: Int, subtitle: String, external: Boolean, onClick: () -> Unit) {
        val row = root.findViewById<View>(id)
        row.findViewById<ImageView>(R.id.row_icon).setImageResource(icon)
        row.findViewById<TextView>(R.id.row_title).setText(title)
        row.findViewById<TextView>(R.id.row_subtitle).text = subtitle
        if (external) row.findViewById<ImageView>(R.id.row_trailing).setImageResource(R.drawable.ic_open_in_new)
        row.setOnClickListener { onClick() }
    }

    fun render(packs: List<ModelPack>) {
        this.packs = packs
        val keep = packs.firstOrNull { it.id == tunePack?.id } ?: ModelPackRepository.selectedPack(activity)
        packGroup.removeAllViews()
        packs.forEachIndexed { index, pack ->
            packGroup.addView(Chip(activity).apply {
                id = View.generateViewId()
                tag = index
                text = pack.displayName
                isCheckable = true
                isCheckedIconVisible = false
                setEnsureMinTouchTargetSize(true)
                chipBackgroundColor = ContextCompat.getColorStateList(activity, R.color.chip_background)
                setTextColor(ContextCompat.getColorStateList(activity, R.color.chip_text))
                chipStrokeColor = ContextCompat.getColorStateList(activity, R.color.chip_stroke)
                chipStrokeWidth = UiKit.dp(activity, 1f).toFloat()
                setTextAppearance(R.style.TextAppearance_PocketTts_LabelMedium)
                isChecked = pack.id == keep?.id
            })
        }
        val hasPacks = packs.isNotEmpty()
        packGroup.visibility = if (hasPacks) View.VISIBLE else View.GONE
        packLabel.visibility = if (hasPacks) View.VISIBLE else View.GONE
        tuneEmpty.visibility = if (hasPacks) View.GONE else View.VISIBLE
        saveButton.visibility = if (hasPacks) View.VISIBLE else View.GONE
        params.forEach { it.visible = hasPacks }
        showParams(keep)
        updateStatus()
    }

    private fun showParams(pack: ModelPack?) {
        tunePack = pack
        params.forEach { it.isEnabled = pack != null }
        temperature.setValue(pack?.let { ModelPackRepository.temperature(activity, it) })
        lsd.setValue(pack?.let { ModelPackRepository.lsdSteps(activity, it).toFloat() })
        threads.setValue(pack?.let { ModelPackRepository.threads(activity, it).toFloat() })
        sentencePause.setValue(pack?.let { ModelPackRepository.sentencePauseMs(activity, it).toFloat() })
        maxTextTokens.setValue(pack?.let { ModelPackRepository.maxTextTokens(activity, it).toFloat() })
    }

    fun updateStatus() {
        val active = activity.isDefaultEngine()
        val color = ContextCompat.getColor(activity, if (active) R.color.pt_success else R.color.pt_warning)
        val soft = ContextCompat.getColor(activity, if (active) R.color.pt_success_soft else R.color.pt_warning_soft)
        statusIcon.background = UiKit.dot(soft, UiKit.dp(activity, 48f))
        statusIcon.setImageResource(if (active) R.drawable.ic_check_circle else R.drawable.ic_warning)
        statusIcon.imageTintList = ColorStateList.valueOf(color)
        statusTitle.setText(if (active) R.string.engine_default_title else R.string.engine_not_default_title)
        statusBody.setText(if (active) R.string.engine_default_body else R.string.engine_not_default_body)
        val entry = UiKit.selectedVoice(activity)
        if (entry != null) {
            voiceAvatar.background = UiKit.avatar(entry.name)
            voiceAvatar.text = entry.initial
            voiceName.text = activity.getString(R.string.voice_detail, entry.voice.displayName, UiKit.languageLabel(entry.pack))
        } else {
            voiceAvatar.background = UiKit.dot(ContextCompat.getColor(activity, R.color.pt_surface_high), UiKit.dp(activity, 36f))
            voiceAvatar.text = "?"
            voiceName.setText(R.string.no_voice)
        }
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        TransitionManager.beginDelayedTransition(root as ViewGroup, AutoTransition().setDuration(220).setInterpolator(UiKit.emphasized))
        tuneBody.visibility = if (value) View.VISIBLE else View.GONE
        tuneChevron.animate().rotation(if (value) 180f else 0f).setDuration(220).setInterpolator(UiKit.emphasized).start()
        tuneSubtitle.setText(if (value) R.string.finetune_subtitle_open else R.string.finetune_subtitle)
    }

    private fun save() {
        val pack = tunePack ?: return
        val temperatureValue = (temperature.value * 100f).roundToInt() / 100f
        val lsdValue = lsd.value.roundToInt()
        val threadsValue = threads.value.roundToInt()
        val pauseValue = sentencePause.value.roundToInt()
        val tokensValue = maxTextTokens.value.roundToInt()
        if (
            temperatureValue !in 0f..2f ||
            lsdValue !in 1..8 ||
            threadsValue !in 1..8 ||
            pauseValue !in 0..2000 ||
            tokensValue !in 10..200
        ) {
            activity.showMessage(activity.getString(R.string.invalid_parameters))
            return
        }
        ModelPackRepository.saveParameters(activity, pack, temperatureValue, lsdValue, threadsValue, pauseValue, tokensValue)
        activity.showMessage(activity.getString(R.string.status_saved, pack.displayName))
    }

    /** A labelled slider row (see `param_slider.xml`) for one generation parameter. */
    private inner class ParamControl(
        containerId: Int,
        @StringRes label: Int,
        @StringRes helper: Int,
        @StringRes description: Int,
        private val from: Float,
        private val to: Float,
        private val step: Float,
        private val format: (Float) -> String
    ) {
        private val container: View = root.findViewById(containerId)
        private val slider: Slider = container.findViewById(R.id.param_slider)
        private val valueView: TextView = container.findViewById(R.id.param_value)

        init {
            container.findViewById<TextView>(R.id.param_label).setText(label)
            container.findViewById<TextView>(R.id.param_helper).setText(helper)
            slider.valueFrom = from
            slider.valueTo = to
            slider.stepSize = step
            slider.value = from
            slider.contentDescription = activity.getString(description)
            // The five included rows share child ids; values are always reloaded from preferences.
            slider.isSaveEnabled = false
            // Discrete ticks only where they stay readable (LSD steps, CPU threads).
            slider.tickVisibilityMode = if ((to - from) / step <= 20f) {
                TickVisibilityMode.TICK_VISIBILITY_AUTO_LIMIT
            } else TickVisibilityMode.TICK_VISIBILITY_HIDDEN
            slider.setLabelFormatter { format(it) }
            slider.addOnChangeListener { _, value, _ -> valueView.text = format(value) }
            valueView.text = EMPTY_VALUE
        }

        val value: Float get() = slider.value

        var visible: Boolean
            get() = container.visibility == View.VISIBLE
            set(value) { container.visibility = if (value) View.VISIBLE else View.GONE }

        var isEnabled: Boolean
            get() = slider.isEnabled
            set(enabled) {
                slider.isEnabled = enabled
                container.alpha = if (enabled) 1f else 0.5f
            }

        /** Shows [newValue] snapped to the slider grid, or an empty state when there is no pack. */
        fun setValue(newValue: Float?) {
            if (newValue == null) {
                slider.value = from
                valueView.text = EMPTY_VALUE
                return
            }
            val steps = ((newValue.coerceIn(from, to) - from) / step).roundToInt()
            slider.value = (from + steps * step).coerceIn(from, to)
            valueView.text = format(slider.value)
        }
    }

    private companion object {
        const val EMPTY_VALUE = "—"
    }
}
