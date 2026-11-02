package org.pockettts.android.engine

import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlin.math.roundToInt

/** The Listen (home) tab: engine status pill, hero actions, recent texts and the reading-app tip. */
internal class ListenTab(private val activity: MainActivity, root: View) {
    private val pill: TextView = root.findViewById(R.id.engine_pill)
    private val recentList: LinearLayout = root.findViewById(R.id.recent_list)
    private val recentEmpty: View = root.findViewById(R.id.recent_empty)

    init {
        // The hero artwork must follow the card's rounded corners.
        root.findViewById<View>(R.id.hero_card).clipToOutline = true
        root.findViewById<View>(R.id.paste_button).setOnClickListener { activity.showPasteSheet() }
        root.findViewById<View>(R.id.open_file_button).setOnClickListener { activity.openTextFile() }
        root.findViewById<View>(R.id.tip_open_settings).setOnClickListener { activity.openTtsSettings() }
    }

    fun refresh() {
        updatePill()
        renderRecents()
    }

    private fun updatePill() {
        val active = activity.isDefaultEngine()
        val color = ContextCompat.getColor(activity, if (active) R.color.pt_success else R.color.pt_warning)
        val soft = ContextCompat.getColor(activity, if (active) R.color.pt_success_soft else R.color.pt_warning_soft)
        pill.setText(if (active) R.string.engine_pill_active else R.string.engine_pill_setup)
        pill.setTextColor(color)
        pill.background = android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(soft),
            UiKit.pill(soft, UiKit.dp(activity, 100f).toFloat()),
            null
        )
        pill.setCompoundDrawablesRelativeWithIntrinsicBounds(UiKit.dot(color, UiKit.dp(activity, 7f)), null, null, null)
        pill.setOnClickListener {
            if (active) activity.showTab(MainActivity.TAB_ENGINE, animate = true) else activity.openTtsSettings()
        }
    }

    private fun renderRecents() {
        val items = RecentTexts.list(activity)
        recentList.removeAllViews()
        recentEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(activity)
        val now = System.currentTimeMillis()
        items.forEach { item ->
            val row = inflater.inflate(R.layout.item_recent, recentList, false)
            row.findViewById<View>(R.id.recent_cover).background =
                UiKit.tile(item.id, UiKit.dp(activity, 16f).toFloat())
            row.findViewById<TextView>(R.id.recent_title).text = item.title
            val time = if (now - item.updatedAt < DateUtils.MINUTE_IN_MILLIS) activity.getString(R.string.recent_just_now)
            else DateUtils.getRelativeTimeSpanString(item.updatedAt, now, DateUtils.MINUTE_IN_MILLIS)
            val finished = item.sentenceCount > 0 && item.sentenceIndex >= item.sentenceCount
            val status = when {
                finished -> activity.getString(R.string.recent_finished)
                item.sentenceIndex <= 0 -> activity.getString(R.string.recent_new)
                else -> activity.getString(R.string.recent_percent, (item.progress * 100).roundToInt().coerceAtLeast(1))
            }
            row.findViewById<TextView>(R.id.recent_meta).text = activity.getString(R.string.recent_meta, status, time)
            row.findViewById<LinearProgressIndicator>(R.id.recent_progress).progress = (item.progress * 1000).roundToInt()
            row.contentDescription = activity.getString(R.string.recent_resume_description, item.title, status)
            row.setOnClickListener { activity.openPlayer(item.id, autoplay = true) }
            row.setOnLongClickListener {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.recent_remove_title)
                    .setMessage(item.title)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.action_remove) { _, _ ->
                        RecentTexts.remove(activity, item.id)
                        renderRecents()
                    }
                    .show()
                true
            }
            recentList.addView(row)
        }
    }
}
