package org.pockettts.android.engine

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.slider.TickVisibilityMode
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var root: View
    private lateinit var packLayout: TextInputLayout
    private lateinit var packDropdown: MaterialAutoCompleteTextView
    private lateinit var voiceLayout: TextInputLayout
    private lateinit var voiceDropdown: MaterialAutoCompleteTextView
    private lateinit var importVoiceButton: MaterialButton
    private lateinit var deletePackButton: MaterialButton
    private lateinit var temperatureInput: ParamControl
    private lateinit var lsdInput: ParamControl
    private lateinit var threadsInput: ParamControl
    private lateinit var sentencePauseInput: ParamControl
    private lateinit var maxTextTokensInput: ParamControl
    private lateinit var statusCard: MaterialCardView
    private lateinit var statusIcon: ImageView
    private lateinit var statusTitle: TextView
    private lateinit var statusBody: TextView
    private lateinit var packChip: Chip
    private lateinit var voiceChip: Chip
    private lateinit var statusProgress: LinearProgressIndicator
    private lateinit var statusView: TextView
    private lateinit var setDefaultButton: MaterialButton
    private var uiCreated = false
    private var packs: List<ModelPack> = emptyList()
    private var visibleVoices: List<PackVoice> = emptyList()
    private var selectedVoiceIndex = -1
    private var currentPack: ModelPack? = null

    override fun onCreate(state: Bundle?) {
        enableEdgeToEdge()
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(state)
        ModelPackRepository.migrateLegacy(this)
        ModelPackRepository.applySystemDefaultSelection(this)
        if (intent?.action == TextToSpeech.Engine.ACTION_CHECK_TTS_DATA) {
            val packs = ModelPackRepository.list(this).filter { it.voices.isNotEmpty() }
            val languages = TtsLocales.availableIds(packs.flatMap { pack ->
                listOf(pack.locale) + pack.voices.map { ModelPackRepository.voiceLocale(pack, it) }
            })
            val result = Intent()
                .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, languages)
                .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf())
            setResult(if (languages.isEmpty()) TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL
                else TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result)
            finish()
            return
        }
        if (intent?.action == TextToSpeech.Engine.ACTION_GET_SAMPLE_TEXT) {
            val requested = TtsLocales.fromLegacy(intent.getStringExtra("language").orEmpty(),
                intent.getStringExtra("country").orEmpty(), intent.getStringExtra("variant").orEmpty())
            val sampleContext = if (requested.language.isBlank()) this else {
                createConfigurationContext(Configuration(resources.configuration).apply { setLocale(requested) })
            }
            setResult(TextToSpeech.LANG_AVAILABLE, Intent()
                .putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, sampleContext.getString(R.string.test_text)))
            finish()
            return
        }
        if (intent?.action == ACTION_SELF_TEST) {
            showSelfTest()
            return
        }
        if (intent?.action == ACTION_MULTI_VOICE_SELF_TEST) {
            showMultiVoiceSelfTest()
            return
        }
        createUi()
        refreshData()
    }

    override fun onResume() {
        super.onResume()
        // The user may have changed the preferred engine in Android's settings meanwhile.
        if (uiCreated) updateEngineStatus()
    }

    private fun createUi() {
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.main_root)
        val scroll = findViewById<View>(R.id.main_scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top + dp(16), bars.right, bars.bottom + dp(32))
            insets
        }

        statusCard = findViewById(R.id.status_card)
        statusIcon = findViewById(R.id.status_icon)
        statusTitle = findViewById(R.id.status_title)
        statusBody = findViewById(R.id.status_body)
        packChip = findViewById(R.id.active_pack_chip)
        voiceChip = findViewById(R.id.active_voice_chip)
        statusProgress = findViewById(R.id.status_progress)
        statusView = findViewById(R.id.status_message)
        setDefaultButton = findViewById(R.id.set_default_button)
        setDefaultButton.setOnClickListener { openTtsSettings() }

        packLayout = findViewById(R.id.pack_layout)
        packDropdown = findViewById(R.id.pack_dropdown)
        voiceLayout = findViewById(R.id.voice_layout)
        voiceDropdown = findViewById(R.id.voice_dropdown)
        importVoiceButton = findViewById(R.id.import_voice_button)
        deletePackButton = findViewById(R.id.delete_pack_button)

        findViewById<View>(R.id.download_packs_button).setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$REPOSITORY_URL/releases/latest")))
            }.onFailure {
                showStatus(getString(R.string.error_open_downloads), notify = true)
            }
        }
        findViewById<View>(R.id.import_pack_button).setOnClickListener { confirmModelPackImport() }
        deletePackButton.setOnClickListener { confirmDeletePack() }
        importVoiceButton.setOnClickListener { openDocument(REQUEST_VOICE, "audio/wav") }

        temperatureInput = ParamControl(R.id.param_temperature, R.string.param_temperature,
            R.string.param_temperature_help, R.string.temperature_label, 0f, 2f, 0.01f) {
            String.format(Locale.getDefault(), "%.2f", it)
        }
        lsdInput = ParamControl(R.id.param_lsd, R.string.param_lsd_steps,
            R.string.param_lsd_steps_help, R.string.lsd_steps_label, 1f, 8f, 1f) { it.roundToInt().toString() }
        threadsInput = ParamControl(R.id.param_threads, R.string.param_cpu_threads,
            R.string.param_cpu_threads_help, R.string.cpu_threads_label, 1f, 8f, 1f) { it.roundToInt().toString() }
        sentencePauseInput = ParamControl(R.id.param_pause, R.string.param_sentence_pause,
            R.string.param_sentence_pause_help, R.string.sentence_pause_label, 0f, 2000f, 1f) {
            getString(R.string.value_milliseconds, it.roundToInt())
        }
        maxTextTokensInput = ParamControl(R.id.param_tokens, R.string.param_max_text_tokens,
            R.string.param_max_text_tokens_help, R.string.max_text_tokens_label, 10f, 200f, 1f) {
            it.roundToInt().toString()
        }

        findViewById<View>(R.id.save_button).setOnClickListener { saveSettings() }
        findViewById<View>(R.id.read_aloud_button).setOnClickListener {
            startActivity(Intent(this@MainActivity, ReadAloudActivity::class.java))
        }
        findViewById<View>(R.id.open_tts_button).setOnClickListener { openTtsSettings() }
        findViewById<View>(R.id.about_button).setOnClickListener { showAbout() }

        packDropdown.setOnItemClickListener { _, _, position, _ ->
            packs.getOrNull(position)?.let {
                ModelPackRepository.selectPack(this@MainActivity, it.id)
                showPack(it)
            }
        }
        voiceDropdown.setOnItemClickListener { _, _, position, _ ->
            val pack = currentPack ?: return@setOnItemClickListener
            visibleVoices.getOrNull(position)?.let {
                selectedVoiceIndex = position
                ModelPackRepository.selectVoice(this@MainActivity, pack.id, it.id)
                updateEngineStatus()
            }
        }
        uiCreated = true
    }

    private fun refreshData(message: String? = null) {
        packs = ModelPackRepository.list(this)
        val selected = ModelPackRepository.selectedPack(this)
        val index = packs.indexOfFirst { it.id == selected?.id }.coerceAtLeast(0)
        packDropdown.setAdapter(DropdownAdapter(this, packs.map { it.displayName }))
        packLayout.isEnabled = packs.isNotEmpty()
        val pack = packs.getOrNull(index)
        packDropdown.setText(pack?.displayName ?: getString(R.string.no_language_pack), false)
        // Persist the effective selection, as the former spinner did on its initial selection.
        pack?.let { ModelPackRepository.selectPack(this, it.id) }
        showPack(pack)
        val status = message ?: if (packs.isEmpty()) {
            getString(R.string.status_no_model)
        } else {
            getString(R.string.status_installed, packs.size)
        }
        showStatus(status, notify = message != null)
    }

    private fun showPack(pack: ModelPack?) {
        currentPack = pack
        visibleVoices = pack?.voices.orEmpty()
        voiceDropdown.setAdapter(DropdownAdapter(this, visibleVoices.map { it.displayName }))
        val selectedVoice = pack?.let { ModelPackRepository.selectedVoiceId(this, it) }
        val voiceIndex = visibleVoices.indexOfFirst { it.id == selectedVoice }.coerceAtLeast(0)
        val voice = visibleVoices.getOrNull(voiceIndex)
        selectedVoiceIndex = if (voice != null) voiceIndex else -1
        voiceDropdown.setText(voice?.displayName ?: getString(R.string.no_voice), false)
        voiceLayout.isEnabled = visibleVoices.isNotEmpty()
        if (pack != null && voice != null) ModelPackRepository.selectVoice(this, pack.id, voice.id)
        importVoiceButton.isEnabled = pack != null
        deletePackButton.isEnabled = pack != null
        temperatureInput.isEnabled = pack != null
        lsdInput.isEnabled = pack != null
        threadsInput.isEnabled = pack != null
        sentencePauseInput.isEnabled = pack != null
        maxTextTokensInput.isEnabled = pack != null
        temperatureInput.setValue(pack?.let { ModelPackRepository.temperature(this, it) })
        lsdInput.setValue(pack?.let { ModelPackRepository.lsdSteps(this, it).toFloat() })
        threadsInput.setValue(pack?.let { ModelPackRepository.threads(this, it).toFloat() })
        sentencePauseInput.setValue(pack?.let { ModelPackRepository.sentencePauseMs(this, it).toFloat() })
        maxTextTokensInput.setValue(pack?.let { ModelPackRepository.maxTextTokens(this, it).toFloat() })
        updateEngineStatus()
    }

    /** Updates the banner: whether Pocket TTS is Android's default engine and what is active. */
    private fun updateEngineStatus() {
        val defaultEngine = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
        }.getOrNull()
        val isDefault = defaultEngine == packageName
        val containerAttr = if (isDefault) {
            com.google.android.material.R.attr.colorPrimaryContainer
        } else com.google.android.material.R.attr.colorTertiaryContainer
        val onContainerAttr = if (isDefault) {
            com.google.android.material.R.attr.colorOnPrimaryContainer
        } else com.google.android.material.R.attr.colorOnTertiaryContainer
        val container = MaterialColors.getColor(statusCard, containerAttr)
        val onContainer = MaterialColors.getColor(statusCard, onContainerAttr)
        statusCard.setCardBackgroundColor(container)
        statusIcon.setImageResource(if (isDefault) R.drawable.ic_check_circle else R.drawable.ic_warning)
        statusIcon.imageTintList = ColorStateList.valueOf(onContainer)
        statusTitle.setText(if (isDefault) R.string.engine_default_title else R.string.engine_not_default_title)
        statusBody.setText(if (isDefault) R.string.engine_default_body else R.string.engine_not_default_body)
        statusTitle.setTextColor(onContainer)
        statusBody.setTextColor(onContainer)
        statusView.setTextColor(onContainer)
        setDefaultButton.visibility = if (isDefault) View.GONE else View.VISIBLE

        val chipBackground = ColorStateList.valueOf(
            MaterialColors.getColor(statusCard, com.google.android.material.R.attr.colorSurfaceContainerLowest)
        )
        listOf(packChip, voiceChip).forEach {
            it.chipBackgroundColor = chipBackground
            it.chipStrokeWidth = 0f
        }
        val pack = currentPack
        packChip.text = pack?.displayName ?: getString(R.string.no_language_pack)
        voiceChip.text = visibleVoices.getOrNull(selectedVoiceIndex)?.displayName ?: getString(R.string.no_voice)
        voiceChip.visibility = if (pack != null) View.VISIBLE else View.GONE
    }

    /** Shows a persistent status line in the banner and, for results of user actions, a snackbar. */
    private fun showStatus(message: String, busy: Boolean = false, notify: Boolean = false) {
        statusView.text = message
        statusProgress.visibility = if (busy) View.VISIBLE else View.GONE
        if (notify) Snackbar.make(root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun confirmDeletePack() {
        val pack = currentPack ?: return
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_delete)
            .setTitle(R.string.delete_pack_title)
            .setMessage(getString(R.string.delete_pack_message, pack.displayName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_language_pack) { _, _ -> deletePack(pack) }
            .show()
    }

    private fun confirmModelPackImport() {
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_warning)
            .setTitle(R.string.import_pack_warning_title)
            .setMessage(R.string.import_pack_warning_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.continue_import) { _, _ ->
                openDocument(REQUEST_MODEL_PACK, "application/zip")
            }
            .show()
    }

    private fun deletePack(pack: ModelPack) {
        showStatus(getString(R.string.status_deleting_pack, pack.displayName), busy = true)
        deletePackButton.isEnabled = false
        thread(name = "pockettts-delete-pack") {
            runCatching { ModelPackRepository.deletePack(this, pack) }
                .onSuccess {
                    runOnUiThread { refreshData(getString(R.string.status_delete_success, pack.displayName)) }
                }
                .onFailure { error ->
                    Log.e(TAG, "DELETE_PACK_FAILED pack=${pack.id}", error)
                    runOnUiThread {
                        deletePackButton.isEnabled = currentPack != null
                        showStatus(getString(R.string.status_delete_failed, error.message.orEmpty()), notify = true)
                    }
                }
        }
    }

    private fun saveSettings() {
        val pack = currentPack ?: return
        val temperature = (temperatureInput.value * 100f).roundToInt() / 100f
        val lsd = lsdInput.value.roundToInt()
        val threads = threadsInput.value.roundToInt()
        val sentencePauseMs = sentencePauseInput.value.roundToInt()
        val maxTextTokens = maxTextTokensInput.value.roundToInt()
        if (
            temperature !in 0f..2f ||
            lsd !in 1..8 ||
            threads !in 1..8 ||
            sentencePauseMs !in 0..2000 ||
            maxTextTokens !in 10..200
        ) {
            showStatus(getString(R.string.invalid_parameters), notify = true)
            return
        }
        ModelPackRepository.saveParameters(
            this,
            pack,
            temperature,
            lsd,
            threads,
            sentencePauseMs,
            maxTextTokens
        )
        visibleVoices.getOrNull(selectedVoiceIndex)?.let {
            ModelPackRepository.selectVoice(this, pack.id, it.id)
        }
        showStatus(getString(R.string.status_saved, pack.displayName), notify = true)
    }

    private fun openDocument(requestCode: Int, mime: String) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            type = if (requestCode == REQUEST_MODEL_PACK) "*/*" else mime
            if (requestCode == REQUEST_MODEL_PACK) putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
            )
            if (requestCode == REQUEST_VOICE) putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/wav", "audio/x-wav", "audio/wave"))
        }, requestCode)
    }

    private fun openTtsSettings() {
        runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
            .onFailure { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_info)
            .setTitle(R.string.about_title)
            .setMessage(getString(R.string.about_message, BuildConfig.VERSION_NAME, REPOSITORY_URL))
            .setNegativeButton(android.R.string.ok, null)
            .setPositiveButton(R.string.open_repository) { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPOSITORY_URL))) }
                    .onFailure {
                        showStatus(getString(R.string.error_open_repository), notify = true)
                    }
            }
            .show()
    }

    @Deprecated("Activity result API is sufficient for this framework-only activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        if (data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) {
            showStatus(getString(R.string.error_untrusted_document), notify = true)
            return
        }
        showStatus(
            if (requestCode == REQUEST_MODEL_PACK) {
                getString(R.string.status_importing_pack)
            } else getString(R.string.status_importing_voice),
            busy = true
        )
        thread(name = "pockettts-import") {
            runCatching {
                when (requestCode) {
                    REQUEST_MODEL_PACK -> ModelPackRepository.importPack(this, uri).displayName
                    REQUEST_VOICE -> {
                        val pack = currentPack ?: error("Kein Sprachpaket ausgewählt")
                        ModelPackRepository.importVoice(this, pack, uri).displayName
                    }
                    else -> return@thread
                }
            }.onSuccess { name -> runOnUiThread { refreshData(getString(R.string.status_import_success, name)) } }
                .onFailure { error ->
                    Log.e(TAG, "IMPORT_FAILED", error)
                    runOnUiThread {
                        showStatus(getString(R.string.status_import_failed, error.message.orEmpty()), notify = true)
                    }
                }
        }
    }

    private fun showSelfTest() {
        val status = selfTestView()
        thread(name = "pockettts-self-test") {
            runCatching { runSelfTest() }
                .onSuccess { result ->
                    Log.i(TAG, "SELF_TEST_OK file=${result.file} samples=${result.samples} chunks=${result.chunks} min=${result.min} max=${result.max}")
                    runOnUiThread {
                        status.text = getString(R.string.self_test_success, result.samples, result.samples / SAMPLE_RATE)
                    }
                }
                .onFailure { error ->
                    Log.e(TAG, "SELF_TEST_FAILED", error)
                    runOnUiThread { status.text = getString(R.string.self_test_failed, error.message.orEmpty()) }
                }
        }
    }

    private fun showMultiVoiceSelfTest() {
        val status = selfTestView()
        thread(name = "pockettts-multi-voice-test") {
            runCatching { runMultiVoiceSelfTest() }
                .onSuccess { result ->
                    Log.i(
                        TAG,
                        "MULTI_VOICE_TEST_OK pack=${result.packId} first=${result.firstVoice} " +
                            "second=${result.secondVoice} firstHash=${result.firstHash} " +
                            "secondHash=${result.secondHash} repeatHash=${result.repeatHash}"
                    )
                    runOnUiThread { status.text = getString(R.string.multi_voice_test_success) }
                }
                .onFailure { error ->
                    Log.e(TAG, "MULTI_VOICE_TEST_FAILED", error)
                    runOnUiThread { status.text = getString(R.string.self_test_failed, error.message.orEmpty()) }
                }
        }
    }

    /** Plain diagnostic screen used by the ADB-only self-test actions. */
    private fun selfTestView(): TextView {
        val status = TextView(this).apply {
            text = getString(R.string.self_test_running)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
        }
        ViewCompat.setOnApplyWindowInsetsListener(status) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left + dp(24), bars.top + dp(36), bars.right + dp(24), bars.bottom + dp(24))
            insets
        }
        setContentView(status)
        return status
    }

    private fun runMultiVoiceSelfTest(): MultiVoiceTestResult {
        val pack = ModelPackRepository.list(this).firstOrNull { it.voices.size >= 2 }
            ?: error(getString(R.string.error_need_two_voices))
        val first = pack.voices[0]
        val second = pack.voices[1]
        NativePocketTts(
            pack.modelsDir.absolutePath,
            pack.voicesDir.absolutePath,
            pack.precision,
            0f,
            ModelPackRepository.lsdSteps(this, pack),
            1,
            ModelPackRepository.sentencePauseMs(this, pack),
            ModelPackRepository.maxTextTokens(this, pack)
        ).use { tts ->
            val firstHash = synthesizeFingerprint(tts, first.fileName)
            val secondHash = synthesizeFingerprint(tts, second.fileName)
            val repeatHash = synthesizeFingerprint(tts, first.fileName)
            check(firstHash == repeatHash) { getString(R.string.error_voice_repeat_mismatch) }
            check(firstHash != secondHash) { getString(R.string.error_voices_not_distinct) }
            return MultiVoiceTestResult(pack.id, first.id, second.id, firstHash, secondHash, repeatHash)
        }
    }

    private fun synthesizeFingerprint(tts: NativePocketTts, voiceFile: String): String {
        var hash = -0x340d631b7bdddcdbL
        var samples = 0L
        check(tts.synthesize(getString(R.string.multi_voice_test_text), voiceFile, object : NativePocketTts.AudioSink {
            override fun onAudio(values: FloatArray): Boolean {
                values.forEach { value ->
                    hash = hash xor value.toBits().toLong()
                    hash *= 0x100000001b3L
                }
                samples += values.size
                return true
            }
        })) { getString(R.string.error_native_synthesis) }
        check(samples > 0) { getString(R.string.error_no_samples) }
        return java.lang.Long.toUnsignedString(hash, 16)
    }

    private fun runSelfTest(): SelfTestResult {
        val (pack, voice) = ModelPackRepository.resolveVoice(this, null) ?: error(getString(R.string.error_no_model_voice))
        val pcm = ByteArrayOutputStream()
        var samples = 0L
        var chunks = 0
        var minimum = Float.POSITIVE_INFINITY
        var maximum = Float.NEGATIVE_INFINITY
        NativePocketTts(
            pack.modelsDir.absolutePath,
            pack.voicesDir.absolutePath,
            pack.precision,
            ModelPackRepository.temperature(this, pack),
            ModelPackRepository.lsdSteps(this, pack),
            ModelPackRepository.threads(this, pack),
            ModelPackRepository.sentencePauseMs(this, pack),
            ModelPackRepository.maxTextTokens(this, pack)
        ).use { tts ->
            check(tts.synthesize(getString(R.string.test_text), voice.fileName, object : NativePocketTts.AudioSink {
                override fun onAudio(values: FloatArray): Boolean {
                    chunks++
                    samples += values.size
                    val bytes = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    values.forEach { value ->
                        minimum = minOf(minimum, value)
                        maximum = maxOf(maximum, value)
                        bytes.putShort((value.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                    }
                    pcm.write(bytes.array())
                    Log.i(TAG, "SELF_TEST_CHUNK index=$chunks samples=${values.size} total=$samples")
                    return true
                }
            })) { getString(R.string.error_native_synthesis) }
        }
        check(samples > 0) { getString(R.string.error_no_samples) }
        val output = File(filesDir, "selftest.wav")
        val audio = pcm.toByteArray()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + audio.size)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16); putShort(1); putShort(1)
            putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(audio.size)
        }.array()
        output.outputStream().use { it.write(header); it.write(audio) }
        return SelfTestResult(output.absolutePath, samples, chunks, minimum, maximum)
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
        private val container: View = findViewById(containerId)
        private val slider: Slider = container.findViewById(R.id.param_slider)
        private val valueView: TextView = container.findViewById(R.id.param_value)

        init {
            container.findViewById<TextView>(R.id.param_label).setText(label)
            container.findViewById<TextView>(R.id.param_helper).setText(helper)
            slider.valueFrom = from
            slider.valueTo = to
            slider.stepSize = step
            slider.value = from
            slider.contentDescription = getString(description)
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

        var isEnabled: Boolean
            get() = slider.isEnabled
            set(enabled) {
                slider.isEnabled = enabled
                container.alpha = if (enabled) 1f else 0.6f
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

    /** Exposed-dropdown adapter that never filters, so every entry stays selectable. */
    private class DropdownAdapter(context: Context, private val items: List<String>) :
        ArrayAdapter<String>(context, R.layout.dropdown_item, items) {
        override fun getFilter(): Filter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?) = FilterResults().apply {
                values = items
                count = items.size
            }
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) = notifyDataSetChanged()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class SelfTestResult(val file: String, val samples: Long, val chunks: Int, val min: Float, val max: Float)
    private data class MultiVoiceTestResult(
        val packId: String,
        val firstVoice: String,
        val secondVoice: String,
        val firstHash: String,
        val secondHash: String,
        val repeatHash: String
    )

    private companion object {
        const val TAG = "PocketTTS"
        const val ACTION_SELF_TEST = "org.pockettts.android.engine.SELF_TEST"
        const val ACTION_MULTI_VOICE_SELF_TEST = "org.pockettts.android.engine.MULTI_VOICE_SELF_TEST"
        const val REPOSITORY_URL = "https://github.com/The-unknown-Shadowman/PocketTTS-Android-Engine"
        const val REQUEST_MODEL_PACK = 1001
        const val REQUEST_VOICE = 1002
        const val SAMPLE_RATE = 24_000
        const val EMPTY_VALUE = "—"
    }
}
