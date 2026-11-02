package org.pockettts.android.engine

import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputFilter
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.color.DynamicColors
import com.google.android.material.textfield.TextInputLayout
import java.util.UUID

/** Receives plain text without following links, opening attachments or speaking automatically. */
class ReadAloudActivity : AppCompatActivity() {
    private lateinit var input: EditText
    private lateinit var play: Button
    private lateinit var stop: Button
    private lateinit var status: TextView
    private lateinit var selection: Chip
    private var tts: TextToSpeech? = null
    private var ready = false
    private var destroyed = false
    private var activeUtterance: String? = null

    override fun onCreate(state: Bundle?) {
        enableEdgeToEdge()
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(state)
        val maxLength = TextToSpeech.getMaxSpeechInputLength()
        setContentView(R.layout.activity_read_aloud)
        val toolbar = findViewById<MaterialToolbar>(R.id.read_aloud_toolbar)
        val actions = findViewById<View>(R.id.read_aloud_actions)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.read_aloud_root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            toolbar.setPadding(bars.left, bars.top, bars.right, 0)
            actions.setPadding(bars.left + dp(16), dp(12), bars.right + dp(16), maxOf(bars.bottom, ime.bottom) + dp(12))
            insets
        }
        toolbar.setNavigationOnClickListener { finish() }
        selection = findViewById(R.id.read_aloud_selection)
        selection.setOnClickListener { openConfiguration() }
        findViewById<TextInputLayout>(R.id.read_aloud_text_layout).counterMaxLength = maxLength
        input = findViewById<EditText>(R.id.read_aloud_text).apply {
            filters = arrayOf(InputFilter.LengthFilter(maxLength))
            // Keep shared text out of automatic view-state persistence.
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        play = findViewById<Button>(R.id.read_aloud_play).apply {
            isEnabled = false
            setOnClickListener { speak() }
        }
        stop = findViewById<Button>(R.id.read_aloud_stop).apply {
            isEnabled = false
            setOnClickListener { stopReading() }
        }
        findViewById<View>(R.id.read_aloud_configure).setOnClickListener { openConfiguration() }
        status = findViewById(R.id.read_aloud_status)

        val shared = if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() }.getOrNull()
        } else null
        if (shared != null && shared.length > maxLength) {
            status.text = getString(R.string.read_aloud_too_long, maxLength)
        } else {
            input.setText(shared.orEmpty())
        }
        // Post the callback so the constructor has assigned tts before it is used.
        val handler = Handler(Looper.getMainLooper())
        tts = TextToSpeech(this, { result -> handler.post {
            if (!destroyed) {
                ready = result == TextToSpeech.SUCCESS
                play.isEnabled = ready
                if (!ready) status.setText(R.string.read_aloud_failed)
            }
        } }, packageName)
        tts?.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = complete(utteranceId, R.string.read_aloud_done)
            @Deprecated("Required by Android's TTS listener")
            override fun onError(utteranceId: String?) = complete(utteranceId, R.string.read_aloud_failed)
            override fun onError(utteranceId: String?, errorCode: Int) =
                complete(utteranceId, R.string.read_aloud_failed)
        })
    }

    override fun onResume() {
        super.onResume()
        // The pack or voice may have changed in the configuration screen.
        val pack = ModelPackRepository.selectedPack(this)
        val voice = pack?.voices?.firstOrNull { it.id == ModelPackRepository.selectedVoiceId(this, pack) }
        selection.text = if (pack != null && voice != null) {
            getString(R.string.selection_summary, pack.displayName, voice.displayName)
        } else getString(R.string.error_no_model_voice)
    }

    private fun openConfiguration() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun speak() {
        val client = tts ?: return
        if (!ready) return
        val text = input.text.toString().trim()
        if (text.isEmpty()) {
            status.setText(R.string.read_aloud_text)
            return
        }
        val pack = ModelPackRepository.selectedPack(this)
        val voice = pack?.voices?.firstOrNull { it.id == ModelPackRepository.selectedVoiceId(this, pack) }
        if (pack == null || voice == null) {
            status.setText(R.string.error_no_model_voice)
            return
        }
        val selected = client.voices?.firstOrNull { it.name == ModelPackRepository.voiceName(pack, voice) }
        if (selected == null || client.setVoice(selected) != TextToSpeech.SUCCESS) {
            status.setText(R.string.read_aloud_failed)
            return
        }
        val id = UUID.randomUUID().toString()
        activeUtterance = id
        play.isEnabled = false
        stop.isEnabled = true
        status.setText(R.string.read_aloud_reading)
        if (client.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id) != TextToSpeech.SUCCESS) {
            complete(id, R.string.read_aloud_failed)
        }
    }

    private fun complete(id: String?, message: Int) = runOnUiThread {
        if (!destroyed && activeUtterance != null && activeUtterance == id) {
            activeUtterance = null
            play.isEnabled = ready
            stop.isEnabled = false
            status.setText(message)
        }
    }

    private fun stopReading() {
        activeUtterance = null
        tts?.stop()
        play.isEnabled = ready
        stop.isEnabled = false
        status.setText(R.string.read_aloud_stopped)
    }

    override fun onStop() {
        if (activeUtterance != null) stopReading()
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
