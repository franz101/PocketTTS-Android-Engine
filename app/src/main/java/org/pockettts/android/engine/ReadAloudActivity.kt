package org.pockettts.android.engine

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.AnimatedVectorDrawable
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The player. Receives plain text (shared or from the app) without following links, opening
 * attachments or speaking shared text automatically, and reads it sentence by sentence
 * through Android's TextToSpeech bound to this engine, highlighting the current sentence.
 */
class ReadAloudActivity : AppCompatActivity() {
    private lateinit var root: View
    private lateinit var recycler: RecyclerView
    private lateinit var titleView: TextView
    private lateinit var voiceAvatar: TextView
    private lateinit var voiceName: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var positionView: TextView
    private lateinit var timeView: TextView
    private lateinit var playButton: View
    private lateinit var playIcon: ImageView
    private lateinit var loading: CircularProgressIndicator
    private lateinit var glow: View
    private lateinit var speedView: TextView
    private lateinit var panel: View

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var destroyed = false
    private var document: ReaderDocument? = null
    private var sourceText: String? = null
    private var displayTitle = ""
    private var recentId: String? = null
    private var current = 0
    private var playing = false
    private var pendingPlay = false
    private var finished = false
    private var generation = 0
    private var enqueuedUntil = -1
    private var speedIndex = DEFAULT_SPEED_INDEX
    private var lastUserScroll = 0L
    private var iconShowsPause = false
    private var glowAnimator: ValueAnimator? = null
    private var sentencesSinceSave = 0
    private val adapter = BlockAdapter()

    override fun onCreate(state: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(state)
        setContentView(R.layout.activity_read_aloud)
        bindViews()

        speedIndex = prefs().getInt(PREF_SPEED, DEFAULT_SPEED_INDEX).coerceIn(SPEEDS.indices)
        val shared = if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() }.getOrNull()
        } else null
        recentId = state?.getString(STATE_RECENT) ?: if (shared == null) intent.getStringExtra(EXTRA_RECENT_ID) else null
        val recent = recentId?.let { RecentTexts.find(this, it) }
        val text = when {
            recentId != null -> RecentTexts.readText(this, recentId!!)
            else -> shared
        }?.let { if (it.length > RecentTexts.MAX_CHARS) it.substring(0, RecentTexts.MAX_CHARS) else it }
        if (text.isNullOrBlank()) {
            showEmpty(R.string.player_nothing_title, R.string.player_nothing_body, R.string.action_open_app) {
                startActivity(Intent(this, MainActivity::class.java)); finish()
            }
            return
        }
        sourceText = text
        displayTitle = recent?.title
            ?: runCatching { intent.getStringExtra(Intent.EXTRA_SUBJECT) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { ReaderText.title(it, 60) }
            ?: ReaderText.title(text)
        titleView.text = displayTitle
        if (UiKit.selectedVoice(this) == null) {
            showNoVoice()
            return
        }
        val wasPlaying = state?.getBoolean(STATE_PLAYING) ?: intent.getBooleanExtra(EXTRA_AUTOPLAY, false)
        val savedIndex = state?.getInt(STATE_INDEX, -1)?.takeIf { it >= 0 } ?: recent?.sentenceIndex ?: 0
        pendingPlay = wasPlaying
        updateVoiceChip()
        updateSpeed()
        prepareDocument(text, savedIndex, recent)
        initTts()
    }

    private fun bindViews() {
        root = findViewById(R.id.player_root)
        recycler = findViewById(R.id.player_text)
        titleView = findViewById(R.id.player_title)
        voiceAvatar = findViewById(R.id.player_voice_avatar)
        voiceName = findViewById(R.id.player_voice_name)
        progressBar = findViewById(R.id.player_progress)
        positionView = findViewById(R.id.player_position)
        timeView = findViewById(R.id.player_time)
        playButton = findViewById(R.id.player_play)
        playIcon = findViewById(R.id.player_play_icon)
        loading = findViewById(R.id.player_loading)
        glow = findViewById(R.id.player_glow)
        speedView = findViewById(R.id.player_speed)
        panel = findViewById(R.id.player_panel)
        val topBar = findViewById<View>(R.id.player_top_bar)
        val topFade = findViewById<View>(R.id.player_top_fade)
        val empty = findViewById<View>(R.id.player_empty)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topBar.updatePadding(top = bars.top)
            topBar.updateLayoutParams<ViewGroup.MarginLayoutParams> { leftMargin = bars.left; rightMargin = bars.right }
            panel.updatePadding(bottom = bars.bottom + dp(14), left = bars.left + dp(24), right = bars.right + dp(24))
            recycler.updatePadding(top = bars.top + dp(80), left = bars.left + dp(26), right = bars.right + dp(26))
            empty.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        panel.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) recycler.updatePadding(bottom = bottom - top + dp(40))
        }
        topBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            topFade.translationY = (bottom - top).toFloat()
        }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        (recycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(view: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) lastUserScroll = SystemClock.uptimeMillis()
            }
        })

        findViewById<View>(R.id.player_back).setOnClickListener { finish() }
        findViewById<View>(R.id.player_voice_chip).setOnClickListener { showVoicePicker() }
        playButton.setOnClickListener { if (playing) pause() else play(if (finished) 0 else current) }
        findViewById<View>(R.id.player_prev).setOnClickListener { jumpTo(current - 1) }
        findViewById<View>(R.id.player_next).setOnClickListener { jumpTo(current + 1) }
        findViewById<View>(R.id.player_restart).setOnClickListener { jumpTo(0) }
        speedView.setOnClickListener {
            speedIndex = (speedIndex + 1) % SPEEDS.size
            prefs().edit().putInt(PREF_SPEED, speedIndex).apply()
            updateSpeed()
            updateProgress()
            if (playing) play(current)
        }
    }

    private fun prepareDocument(text: String, startIndex: Int, recent: RecentItem?) {
        val locale = UiKit.selectedVoice(this)?.pack?.locale ?: Locale.getDefault()
        val maxChars = TextToSpeech.getMaxSpeechInputLength() - 1
        setControlsEnabled(false)
        thread(name = "pockettts-reader-split") {
            val doc = ReaderText.prepare(text, locale, maxChars)
            main.post {
                if (destroyed) return@post
                if (doc.size == 0) {
                    showEmpty(R.string.player_nothing_title, R.string.player_nothing_body, R.string.action_open_app) {
                        startActivity(Intent(this, MainActivity::class.java)); finish()
                    }
                    return@post
                }
                document = doc
                // A finished text starts again from the beginning.
                current = if (recent != null && recent.sentenceCount > 0 && startIndex >= doc.size) 0
                else startIndex.coerceIn(0, doc.size - 1)
                adapter.current = current
                adapter.notifyDataSetChanged()
                setControlsEnabled(true)
                updateProgress()
                recycler.post { scrollToCurrent(smooth = false, force = true) }
                if (pendingPlay && ttsReady) play(current)
            }
        }
    }

    private fun initTts() {
        tts = TextToSpeech(this, { result ->
            main.post {
                if (destroyed) return@post
                ttsReady = result == TextToSpeech.SUCCESS
                if (!ttsReady) {
                    showEmpty(R.string.player_engine_failed_title, R.string.read_aloud_failed, R.string.navigate_back) { finish() }
                    return@post
                }
                tts?.setSpeechRate(SPEEDS[speedIndex])
                if (pendingPlay && document != null) play(current)
            }
        }, packageName)
        tts?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = dispatch(utteranceId) { onSentenceStarted(it) }
            override fun onDone(utteranceId: String?) = dispatch(utteranceId) { onSentenceDone(it) }
            @Deprecated("Required by Android's TTS listener")
            override fun onError(utteranceId: String?) = dispatch(utteranceId) { onSentenceError() }
            override fun onError(utteranceId: String?, errorCode: Int) = dispatch(utteranceId) { onSentenceError() }
        })
    }

    /** Utterance ids are "generation:index"; callbacks from an older generation are ignored. */
    private fun dispatch(utteranceId: String?, action: (Int) -> Unit) {
        val parts = utteranceId?.split(':') ?: return
        val gen = parts.getOrNull(0)?.toIntOrNull() ?: return
        val index = parts.getOrNull(1)?.toIntOrNull() ?: return
        main.post { if (!destroyed && gen == generation && playing) action(index) }
    }

    private fun play(from: Int) {
        val doc = document ?: run { pendingPlay = true; return }
        val client = tts
        if (client == null || !ttsReady) { pendingPlay = true; return }
        pendingPlay = false
        if (!applyVoice(client)) {
            setPlaying(false)
            Snackbar.make(root, R.string.read_aloud_failed, Snackbar.LENGTH_LONG).setAnchorView(panel).show()
            return
        }
        if (recentId == null) {
            // Shared text joins "Continue listening" once the user actually starts listening.
            recentId = runCatching { RecentTexts.add(this, displayTitle, sourceText.orEmpty()).id }.getOrNull()
        }
        finished = false
        generation++
        setHighlight(from.coerceIn(0, doc.size - 1), animate = true)
        enqueuedUntil = current - 1
        setPlaying(true)
        loading.visibility = View.VISIBLE
        client.setSpeechRate(SPEEDS[speedIndex])
        enqueueThrough(current + QUEUE_AHEAD - 1, flush = true)
    }

    private fun enqueueThrough(last: Int, flush: Boolean = false) {
        val doc = document ?: return
        val client = tts ?: return
        var first = flush
        while (enqueuedUntil < minOf(last, doc.size - 1)) {
            val index = enqueuedUntil + 1
            val mode = if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            first = false
            if (client.speak(doc.text(index), mode, Bundle(), "$generation:$index") != TextToSpeech.SUCCESS) {
                Log.w(TAG, "READER_ENQUEUE_FAILED index=$index")
                onSentenceError()
                return
            }
            enqueuedUntil = index
        }
    }

    private fun onSentenceStarted(index: Int) {
        loading.visibility = View.GONE
        if (index != current) setHighlight(index, animate = true)
        enqueueThrough(index + QUEUE_AHEAD - 1)
        if (++sentencesSinceSave >= SAVE_EVERY) saveProgress()
    }

    private fun onSentenceDone(index: Int) {
        val doc = document ?: return
        enqueueThrough(index + QUEUE_AHEAD)
        if (index >= doc.size - 1) {
            finished = true
            setPlaying(false)
            generation++
            recentId?.let { RecentTexts.updateProgress(this, it, doc.size, doc.size) }
            updateProgress()
        }
    }

    private fun onSentenceError() {
        pause()
        Snackbar.make(root, R.string.read_aloud_failed, Snackbar.LENGTH_LONG).setAnchorView(panel).show()
    }

    private fun pause() {
        generation++
        pendingPlay = false
        tts?.stop()
        setPlaying(false)
        saveProgress()
    }

    private fun jumpTo(index: Int) {
        val doc = document ?: return
        val target = index.coerceIn(0, doc.size - 1)
        finished = false
        lastUserScroll = 0L
        if (playing) play(target) else {
            setHighlight(target, animate = true)
            saveProgress()
        }
    }

    /** Selects the voice chosen in Pocket TTS on the TextToSpeech client. */
    private fun applyVoice(client: TextToSpeech): Boolean {
        val entry = UiKit.selectedVoice(this) ?: return false
        val voice = runCatching { client.voices }.getOrNull()?.firstOrNull { it.name == entry.name } ?: return false
        return client.setVoice(voice) == TextToSpeech.SUCCESS
    }

    private fun setHighlight(index: Int, animate: Boolean) {
        val doc = document ?: return
        val old = current
        current = index.coerceIn(0, doc.size - 1)
        adapter.current = current
        adapter.animateNext = animate
        val oldBlock = doc.sentences[old.coerceIn(0, doc.size - 1)].block
        val newBlock = doc.sentences[current].block
        adapter.notifyItemRangeChanged(minOf(oldBlock, newBlock), kotlin.math.abs(newBlock - oldBlock) + 1)
        updateProgress()
        recycler.post { scrollToCurrent(smooth = true, force = false) }
    }

    private fun scrollToCurrent(smooth: Boolean, force: Boolean, retry: Boolean = true) {
        val doc = document ?: return
        if (!force && SystemClock.uptimeMillis() - lastUserScroll < USER_SCROLL_GRACE_MS) return
        val sentence = doc.sentences[current]
        val manager = recycler.layoutManager as LinearLayoutManager
        val visibleTop = recycler.paddingTop
        val visibleHeight = recycler.height - recycler.paddingTop - recycler.paddingBottom
        val anchor = visibleTop + (visibleHeight * 0.22f).toInt()
        val view = manager.findViewByPosition(sentence.block) as? ReaderTextView
        if (view == null) {
            manager.scrollToPositionWithOffset(sentence.block, anchor)
            if (retry) recycler.post { scrollToCurrent(smooth = false, force = true, retry = false) }
            return
        }
        val y = view.top + view.lineTopFor(sentence.start)
        val comfortable = y in visibleTop..(visibleTop + (visibleHeight * 0.6f).toInt())
        if (comfortable && !force) return
        val dy = y - anchor
        if (smooth) recycler.smoothScrollBy(0, dy, UiKit.emphasized, 600) else recycler.scrollBy(0, dy)
    }

    private fun updateProgress() {
        val doc = document ?: return
        val speed = SPEEDS[speedIndex]
        val done = if (finished) doc.totalChars else doc.charsBefore(current)
        progressBar.setProgressCompat((done * 1000L / doc.totalChars.coerceAtLeast(1)).toInt(), true)
        positionView.text = getString(R.string.player_position, current + 1, doc.size)
        val elapsed = (done / (CHARS_PER_SECOND * speed)).toLong()
        val total = (doc.totalChars / (CHARS_PER_SECOND * speed)).toLong()
        timeView.text = getString(R.string.player_time, UiKit.formatDuration(elapsed), UiKit.formatDuration(total))
    }

    private fun updateSpeed() {
        val speed = SPEEDS[speedIndex]
        val label = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')
        speedView.text = getString(R.string.player_speed, label)
        speedView.contentDescription = getString(R.string.player_speed_description, label)
        tts?.setSpeechRate(speed)
    }

    private fun updateVoiceChip() {
        val entry = UiKit.selectedVoice(this) ?: return
        voiceAvatar.background = UiKit.avatar(entry.name)
        voiceAvatar.text = entry.initial
        voiceName.text = entry.voice.displayName
    }

    private fun setPlaying(value: Boolean) {
        playing = value
        if (!value) loading.visibility = View.GONE
        playButton.contentDescription = getString(if (value) R.string.player_pause else R.string.player_play)
        keepScreenOn(value)
        if (iconShowsPause != value) {
            iconShowsPause = value
            val avd = ContextCompat.getDrawable(this, if (value) R.drawable.avd_play_to_pause else R.drawable.avd_pause_to_play)
            playIcon.setImageDrawable(avd)
            (avd as? AnimatedVectorDrawable)?.start()
        }
        if (value) startGlow() else stopGlow()
    }

    private fun keepScreenOn(on: Boolean) {
        root.keepScreenOn = on
    }

    private fun startGlow() {
        if (glowAnimator != null) return
        glowAnimator = ObjectAnimator.ofPropertyValuesHolder(
            glow,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.92f, 1.08f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.92f, 1.08f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.75f, 1f)
        ).apply {
            duration = 1400
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopGlow() {
        glowAnimator?.cancel()
        glowAnimator = null
        glow.animate().scaleX(1f).scaleY(1f).alpha(0.75f).setDuration(250).start()
    }

    private fun setControlsEnabled(enabled: Boolean) {
        listOf(R.id.player_play, R.id.player_prev, R.id.player_next, R.id.player_restart).forEach {
            findViewById<View>(it).isEnabled = enabled
            findViewById<View>(it).alpha = if (enabled) 1f else 0.4f
        }
    }

    private fun showVoicePicker() {
        val voices = UiKit.allVoices(this)
        if (voices.isEmpty()) { showNoVoice(); return }
        val dialog = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.sheet_voice_picker, null)
        val list = content.findViewById<LinearLayout>(R.id.picker_list)
        val selected = UiKit.selectedVoice(this)?.name
        voices.forEach { entry ->
            val row = layoutInflater.inflate(R.layout.item_voice_row, list, false)
            row.findViewById<TextView>(R.id.row_avatar).apply {
                background = UiKit.avatar(entry.name)
                text = entry.initial
            }
            row.findViewById<TextView>(R.id.row_name).text = entry.voice.displayName
            row.findViewById<TextView>(R.id.row_detail).text = getString(
                R.string.voice_detail, UiKit.languageLabel(entry.pack), UiKit.qualityLabel(this, entry.pack)
            )
            row.findViewById<View>(R.id.row_check).visibility = if (entry.name == selected) View.VISIBLE else View.INVISIBLE
            row.setOnClickListener {
                dialog.dismiss()
                if (entry.name == selected) return@setOnClickListener
                UiKit.select(this, entry)
                updateVoiceChip()
                if (playing) play(current)
            }
            list.addView(row)
        }
        dialog.setContentView(content)
        dialog.show()
    }

    private fun showNoVoice() {
        showEmpty(R.string.player_no_voice_title, R.string.player_no_voice_body, R.string.action_go_to_voices) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_VOICES)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
        }
    }

    private fun showEmpty(title: Int, body: Int, action: Int, onAction: () -> Unit) {
        findViewById<TextView>(R.id.player_empty_title).setText(title)
        findViewById<TextView>(R.id.player_empty_body).setText(body)
        findViewById<MaterialButton>(R.id.player_empty_action).apply {
            setText(action)
            setOnClickListener { onAction() }
        }
        UiKit.fadeIn(findViewById(R.id.player_empty))
        findViewById<View>(R.id.player_voice_chip).visibility = View.GONE
    }

    private fun saveProgress() {
        sentencesSinceSave = 0
        val doc = document ?: return
        val id = recentId ?: return
        if (!finished) RecentTexts.updateProgress(this, id, current, doc.size)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_INDEX, current)
        outState.putBoolean(STATE_PLAYING, playing)
        recentId?.let { outState.putString(STATE_RECENT, it) }
    }

    override fun onStop() {
        // Without a media session the process may be frozen in the background; pause cleanly.
        if (playing && !isChangingConfigurations) pause() else saveProgress()
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        glowAnimator?.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dp(value: Int): Int = UiKit.dp(this, value.toFloat())

    private class BlockHolder(val text: ReaderTextView) : RecyclerView.ViewHolder(text)

    /** Paragraph blocks of the document; read text is dimmed and the current sentence highlighted. */
    private inner class BlockAdapter : RecyclerView.Adapter<BlockHolder>() {
        var current = 0
        var animateNext = false
        private val readColor by lazy { ContextCompat.getColor(this@ReadAloudActivity, R.color.player_text_read) }

        override fun getItemCount(): Int = document?.blocks?.size ?: 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BlockHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_paragraph, parent, false) as ReaderTextView
            val holder = BlockHolder(view)
            view.onOffsetTapped = { offset ->
                val position = holder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) document?.sentenceAt(position, offset)?.let { jumpTo(it) }
            }
            return holder
        }

        override fun onBindViewHolder(holder: BlockHolder, position: Int) {
            val doc = document ?: return
            val block = doc.blocks[position]
            val sentence = doc.sentences[current.coerceIn(0, doc.size - 1)]
            val spannable = SpannableString(block.text)
            when {
                sentence.block > position ->
                    spannable.setSpan(ForegroundColorSpan(readColor), 0, block.text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sentence.block == position && sentence.start > 0 ->
                    spannable.setSpan(ForegroundColorSpan(readColor), 0, sentence.start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            holder.text.text = spannable
            holder.text.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = if (block.continuation || position == 0) 0 else dp(30)
            }
            if (sentence.block == position) holder.text.setHighlight(sentence.start, sentence.end, animateNext)
            else holder.text.setHighlight(-1, -1, false)
        }
    }

    companion object {
        const val EXTRA_RECENT_ID = "org.pockettts.android.engine.extra.RECENT_ID"
        const val EXTRA_AUTOPLAY = "org.pockettts.android.engine.extra.AUTOPLAY"
        private const val TAG = "PocketTTS"
        private const val PREFS = "pockettts_player"
        private const val PREF_SPEED = "speed_index"
        private const val STATE_INDEX = "index"
        private const val STATE_PLAYING = "playing"
        private const val STATE_RECENT = "recent"
        private val SPEEDS = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        private const val DEFAULT_SPEED_INDEX = 1
        private const val QUEUE_AHEAD = 3
        private const val SAVE_EVERY = 5
        private const val CHARS_PER_SECOND = 15f
        private const val USER_SCROLL_GRACE_MS = 4_000L
    }
}
