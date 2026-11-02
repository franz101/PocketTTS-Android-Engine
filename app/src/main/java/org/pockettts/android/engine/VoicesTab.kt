package org.pockettts.android.engine

import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlin.concurrent.thread

/** The Voices tab: a grid of voice cards, voice cloning, and the installed language packs. */
internal class VoicesTab(private val activity: MainActivity, private val rootView: View) {
    private val root = rootView
    private val grid: LinearLayout = root.findViewById(R.id.voice_grid)
    private val empty: View = root.findViewById(R.id.voices_empty)
    private val busyCard: View = root.findViewById(R.id.voices_busy)
    private val busyText: TextView = root.findViewById(R.id.voices_busy_text)
    private val cloneCard: View = root.findViewById(R.id.clone_card)
    private val packList: LinearLayout = root.findViewById(R.id.pack_list)
    private val packsSection: View = root.findViewById(R.id.packs_section)
    private val main = Handler(Looper.getMainLooper())
    private val cards = LinkedHashMap<String, Card>()
    private val sizeCache = HashMap<String, Long>()
    private val accent = ContextCompat.getColor(activity, R.color.pt_accent)
    private val outline = ContextCompat.getColor(activity, R.color.pt_outline)
    private val surface = ContextCompat.getColor(activity, R.color.pt_surface)
    private val selectedSurface = ColorUtils.compositeColors(
        ContextCompat.getColor(activity, R.color.pt_accent_soft), surface
    )

    private class Card(
        val entry: VoiceEntry,
        val view: MaterialCardView,
        val check: View,
        val previewIcon: ImageView,
        val previewProgress: CircularProgressIndicator
    )

    init {
        cloneCard.setOnClickListener { activity.startVoiceImport() }
        root.findViewById<View>(R.id.import_pack_button).setOnClickListener { activity.confirmModelPackImport() }
        root.findViewById<View>(R.id.download_packs_button).visibility = View.GONE
        root.findViewById<View>(R.id.empty_import_pack).setOnClickListener { activity.confirmModelPackImport() }
        root.findViewById<View>(R.id.empty_get_packs).setOnClickListener { activity.downloadFastPack() }
    }

    /** The empty state's main button mirrors the recommended pack's download. */
    private fun refreshEmptyButton() {
        val button = rootView.findViewById<TextView>(R.id.empty_get_packs)
        val item = ModelHub.find(activity, ModelHub.RECOMMENDED_ID) ?: return
        val state = ModelHub.state(item.id)
        button.isEnabled = state == null
        button.text = when (state) {
            null -> activity.getString(R.string.action_download_fast_pack, UiKit.formatBytes(activity, item.size))
            ModelHub.State.Queued -> activity.getString(R.string.hub_queued)
            ModelHub.State.Installing -> activity.getString(R.string.hub_installing)
            is ModelHub.State.Downloading ->
                activity.getString(R.string.hub_button_progress, (100 * state.done / state.total).toInt())
        }
    }

    fun render(packs: List<ModelPack>) {
        refreshEmptyButton()
        val inflater = LayoutInflater.from(activity)
        val entries = packs.flatMap { pack -> pack.voices.map { VoiceEntry(pack, it) } }
        cards.clear()
        grid.removeAllViews()
        empty.visibility = if (packs.isEmpty()) View.VISIBLE else View.GONE
        cloneCard.visibility = if (packs.isEmpty()) View.GONE else View.VISIBLE
        packsSection.visibility = if (packs.isEmpty()) View.GONE else View.VISIBLE
        grid.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        val gap = UiKit.dp(activity, 12f)
        entries.chunked(2).forEach { pair ->
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { index, entry ->
                val card = inflater.inflate(R.layout.item_voice, row, false) as MaterialCardView
                if (index == 1) (card.layoutParams as LinearLayout.LayoutParams).marginStart = gap
                bindCard(card, entry)
                row.addView(card)
            }
            if (pair.size == 1) row.addView(View(activity), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = gap })
            grid.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = gap
            })
        }
        updateSelection(animate = false)
        renderPacks(packs)
        renderHub()
    }

    // ---- Model hub --------------------------------------------------------
    private val hubList: LinearLayout = rootView.findViewById(R.id.hub_list)
    private val hubSection: View = rootView.findViewById(R.id.hub_section)
    private val hubViews = HashMap<String, View>()

    private fun renderHub() {
        hubList.removeAllViews()
        hubViews.clear()
        val inflater = LayoutInflater.from(activity)
        val items = ModelHub.catalog(activity).sortedWith(
            compareByDescending<ModelHub.Item> { it.recommended }
                .thenBy { UiKit.languageName(it.locale) }
                .thenBy { it.tier.ordinal }
        )
        items.forEach { item ->
            val view = inflater.inflate(R.layout.item_hub, hubList, false)
            view.findViewById<TextView>(R.id.hub_code).text = item.locale.language.uppercase(java.util.Locale.ROOT)
            view.findViewById<TextView>(R.id.hub_name).text = UiKit.languageName(item.locale)
            view.findViewById<TextView>(R.id.hub_badge).text = activity.getString(
                when (item.tier) {
                    ModelHub.Tier.FAST -> R.string.badge_fast
                    ModelHub.Tier.HQ -> R.string.badge_hq
                    ModelHub.Tier.EXPERIMENTAL -> R.string.hub_tier_experimental
                }
            )
            view.findViewById<TextView>(R.id.hub_meta).text = activity.getString(
                R.string.hub_meta,
                UiKit.formatBytes(activity, item.size),
                activity.getString(
                    when (item.tier) {
                        ModelHub.Tier.FAST -> R.string.hub_note_fast
                        ModelHub.Tier.HQ -> R.string.hub_note_hq
                        ModelHub.Tier.EXPERIMENTAL -> R.string.hub_note_experimental
                    }
                )
            )
            view.findViewById<View>(R.id.hub_action).setOnClickListener { activity.downloadHubItem(item) }
            view.findViewById<View>(R.id.hub_cancel).setOnClickListener { ModelHub.cancel(item.id) }
            hubList.addView(view)
            hubViews[item.id] = view
            updateHubItem(item.id)
        }
    }

    fun updateHubItem(id: String) {
        if (id == ModelHub.RECOMMENDED_ID) refreshEmptyButton()
        val view = hubViews[id] ?: return
        val item = ModelHub.find(activity, id) ?: return
        val action = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.hub_action)
        val group = view.findViewById<View>(R.id.hub_progress_group)
        val bar = view.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.hub_progress)
        val status = view.findViewById<TextView>(R.id.hub_status)
        val cancel = view.findViewById<View>(R.id.hub_cancel)
        val installed = ModelHub.isInstalled(activity, id)
        view.findViewById<View>(R.id.hub_recommended).visibility =
            if (item.recommended && !installed) View.VISIBLE else View.GONE
        when (val state = ModelHub.state(id)) {
            null -> {
                group.visibility = View.GONE
                action.visibility = View.VISIBLE
                action.isEnabled = !installed
                action.text = activity.getString(if (installed) R.string.hub_installed else R.string.hub_download)
                action.setIconResource(if (installed) R.drawable.ic_check else R.drawable.ic_download)
            }
            else -> {
                action.visibility = View.GONE
                if (group.visibility != View.VISIBLE) UiKit.fadeIn(group)
                cancel.visibility = if (state is ModelHub.State.Installing) View.INVISIBLE else View.VISIBLE
                when (state) {
                    ModelHub.State.Queued -> {
                        bar.isIndeterminate = false
                        bar.setProgressCompat(0, false)
                        status.text = activity.getString(R.string.hub_queued)
                    }
                    is ModelHub.State.Downloading -> {
                        bar.isIndeterminate = false
                        bar.setProgressCompat((1000 * state.done / state.total).toInt(), true)
                        status.text = activity.getString(
                            R.string.hub_progress,
                            (100 * state.done / state.total).toInt(),
                            UiKit.formatBytes(activity, state.done),
                            UiKit.formatBytes(activity, state.total)
                        )
                    }
                    ModelHub.State.Installing -> {
                        if (!bar.isIndeterminate) {
                            bar.visibility = View.INVISIBLE
                            bar.isIndeterminate = true
                            bar.visibility = View.VISIBLE
                        }
                        status.text = activity.getString(R.string.hub_installing)
                    }
                }
            }
        }
        view.contentDescription = listOf(
            view.findViewById<TextView>(R.id.hub_name).text, status.text.takeIf { group.visibility == View.VISIBLE }
        ).filterNotNull().joinToString(", ")
    }

    fun scrollToHub() {
        val scroll = rootView as? androidx.core.widget.NestedScrollView ?: return
        scroll.post { scroll.smoothScrollTo(0, hubSection.top - UiKit.dp(activity, 12f)) }
    }

    private fun bindCard(view: MaterialCardView, entry: VoiceEntry) {
        view.findViewById<TextView>(R.id.voice_avatar).apply {
            background = UiKit.avatar(entry.name)
            text = entry.initial
        }
        view.findViewById<TextView>(R.id.voice_name).text = entry.voice.displayName
        view.findViewById<TextView>(R.id.voice_language).text = UiKit.languageLabel(entry.pack)
        view.findViewById<TextView>(R.id.voice_badge).apply {
            text = UiKit.qualityLabel(activity, entry.pack)
            if (UiKit.isFastPack(entry.pack)) {
                val bolt = ContextCompat.getDrawable(activity, R.drawable.ic_bolt)?.mutate()?.apply {
                    setTint(ContextCompat.getColor(activity, R.color.pt_accent_text))
                    val size = UiKit.dp(activity, 12f)
                    setBounds(0, 0, size, size)
                }
                setCompoundDrawablesRelative(bolt, null, null, null)
                compoundDrawablePadding = UiKit.dp(activity, 3f)
            }
        }
        val preview = view.findViewById<View>(R.id.voice_preview)
        preview.contentDescription = activity.getString(R.string.voice_preview_description, entry.voice.displayName)
        preview.setOnClickListener { activity.preview.toggle(entry) }
        view.contentDescription = activity.getString(
            R.string.voice_card_description, entry.voice.displayName, UiKit.languageLabel(entry.pack)
        )
        view.setOnClickListener {
            if (UiKit.selectedVoice(activity)?.name != entry.name) {
                UiKit.select(activity, entry)
                activity.onVoiceSelected()
            }
        }
        cards[entry.name] = Card(
            entry, view, view.findViewById(R.id.voice_check),
            view.findViewById(R.id.voice_preview_icon), view.findViewById(R.id.voice_preview_progress)
        )
    }

    fun updateSelection(animate: Boolean) {
        val selected = UiKit.selectedVoice(activity)?.name
        cards.values.forEach { card ->
            val isSelected = card.entry.name == selected
            card.view.isSelected = isSelected
            card.view.strokeColor = if (isSelected) accent else outline
            card.view.strokeWidth = UiKit.dp(activity, if (isSelected) 2f else 1f)
            card.view.setCardBackgroundColor(if (isSelected) selectedSurface else surface)
            val wasVisible = card.check.visibility == View.VISIBLE
            if (isSelected && (!wasVisible || !animate)) {
                card.check.visibility = View.VISIBLE
                if (animate) {
                    card.check.scaleX = 0f
                    card.check.scaleY = 0f
                    card.check.animate().scaleX(1f).scaleY(1f).setDuration(280)
                        .setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
                }
            } else if (!isSelected) {
                card.check.animate().cancel()
                card.check.visibility = View.GONE
            }
        }
    }

    fun setPreviewState(name: String?, state: VoicePreview.State) {
        cards.values.forEach { card ->
            val active = card.entry.name == name
            val cardState = if (active) state else if (card.previewProgress.visibility == View.VISIBLE ||
                card.previewIcon.tag == PLAYING_TAG) VoicePreview.State.IDLE else null
            when (cardState) {
                VoicePreview.State.LOADING -> {
                    card.previewProgress.visibility = View.VISIBLE
                    card.previewIcon.setImageResource(R.drawable.ic_stop)
                    card.previewIcon.tag = PLAYING_TAG
                }
                VoicePreview.State.PLAYING -> {
                    card.previewProgress.visibility = View.GONE
                    card.previewIcon.setImageResource(R.drawable.ic_stop)
                    card.previewIcon.tag = PLAYING_TAG
                    card.previewIcon.imageTintList = ColorStateList.valueOf(accent)
                }
                VoicePreview.State.IDLE -> {
                    card.previewProgress.visibility = View.GONE
                    card.previewIcon.setImageResource(R.drawable.ic_play_rounded)
                    card.previewIcon.tag = null
                    card.previewIcon.imageTintList =
                        ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.pt_text))
                }
                null -> Unit
            }
        }
    }

    private fun renderPacks(packs: List<ModelPack>) {
        packList.removeAllViews()
        val inflater = LayoutInflater.from(activity)
        packs.forEach { pack ->
            val view = inflater.inflate(R.layout.item_pack, packList, false)
            view.findViewById<TextView>(R.id.pack_code).text = UiKit.languageCode(pack)
            view.findViewById<TextView>(R.id.pack_name).text = pack.displayName
            view.findViewById<TextView>(R.id.pack_badge).text = UiKit.qualityLabel(activity, pack)
            val meta = view.findViewById<TextView>(R.id.pack_meta)
            fun showMeta(size: Long?) {
                val voices = activity.resources.getQuantityString(R.plurals.voice_count, pack.voices.size, pack.voices.size)
                meta.text = listOfNotNull(UiKit.languageLabel(pack), voices, size?.let { UiKit.formatBytes(activity, it) })
                    .joinToString(" · ")
            }
            showMeta(sizeCache[pack.id])
            if (sizeCache[pack.id] == null) {
                thread(name = "pockettts-pack-size") {
                    val size = runCatching { UiKit.sizeOnDisk(pack.root) }.getOrNull() ?: return@thread
                    main.post {
                        sizeCache[pack.id] = size
                        if (view.parent === packList) showMeta(size)
                    }
                }
            }
            view.findViewById<View>(R.id.pack_menu).setOnClickListener { anchor ->
                PopupMenu(activity, anchor).apply {
                    inflate(R.menu.pack_menu)
                    setOnMenuItemClickListener {
                        activity.confirmDeletePack(pack)
                        true
                    }
                    show()
                }
            }
            packList.addView(view)
        }
        // Sizes change after an import or deletion; recompute next time.
        sizeCache.keys.retainAll(packs.map { it.id }.toSet())
    }

    fun invalidateSizes() = sizeCache.clear()

    fun setBusy(message: String?) {
        if (message == null) {
            busyCard.visibility = View.GONE
            invalidateSizes()
        } else {
            busyText.text = message
            UiKit.fadeIn(busyCard)
        }
    }

    private companion object {
        const val PLAYING_TAG = "playing"
    }
}
