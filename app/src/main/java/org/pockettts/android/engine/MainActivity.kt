package org.pockettts.android.engine

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.NumberFormat
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var root: View
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var screens: List<View>
    private lateinit var listenTab: ListenTab
    private lateinit var voicesTab: VoicesTab
    private lateinit var engineTab: EngineTab
    private var uiCreated = false
    private var currentTab = TAB_LISTEN
    private var voiceImportPackId: String? = null
    internal var packs: List<ModelPack> = emptyList()
        private set
    internal val preview: VoicePreview by lazy {
        VoicePreview(this) { name, state -> if (uiCreated) voicesTab.setPreviewState(name, state) }
    }

    override fun onCreate(state: Bundle?) {
        enableEdgeToEdge()
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
        voiceImportPackId = state?.getString(STATE_VOICE_PACK)
        createUi()
        refreshData()
        val initialTab = state?.getInt(STATE_TAB) ?: tabFromIntent(intent)
        showTab(initialTab, animate = false)
        ModelHub.addListener(hubListener)
        // First launch with nothing installed: fetch the recommended English pack
        // automatically when that can't cost mobile data; otherwise one tap on Voices.
        if (state == null && packs.isEmpty() && !ModelHub.isBusy && ModelHub.isUnmetered(this)) {
            ModelHub.find(this, ModelHub.RECOMMENDED_ID)?.let { ModelHub.enqueue(this, it) }
            showTab(TAB_VOICES, animate = false)
        }
    }

    private val hubListener: (ModelHub.Event) -> Unit = { event ->
        if (uiCreated) when (event) {
            is ModelHub.Event.Progress -> voicesTab.updateHubItem(event.id)
            is ModelHub.Event.Installed -> refreshData(
                getString(R.string.hub_done, UiKit.languageLabel(event.pack))
            )
            is ModelHub.Event.Failed -> {
                voicesTab.updateHubItem(event.id)
                showMessage(getString(R.string.status_download_failed, event.message))
            }
            is ModelHub.Event.Cancelled -> {
                voicesTab.updateHubItem(event.id)
                showMessage(getString(R.string.status_download_cancelled))
            }
        }
    }

    /** Recommended English pack, from the empty state. */
    internal fun downloadFastPack() {
        ModelHub.find(this, ModelHub.RECOMMENDED_ID)?.let { downloadHubItem(it) }
    }

    internal fun downloadHubItem(item: ModelHub.Item) {
        val go = {
            ModelHub.enqueue(this, item)
            showTab(TAB_VOICES, animate = true)
        }
        if (ModelHub.isUnmetered(this)) go() else MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hub_mobile_data_title)
            .setMessage(getString(R.string.hub_mobile_data_message, UiKit.formatBytes(this, item.size)))
            .setPositiveButton(R.string.hub_download) { _, _ -> go() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** "Get packs" anywhere in the app: jump to the model hub. */
    internal fun getPacks() {
        showTab(TAB_VOICES, animate = true)
        voicesTab.scrollToHub()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (uiCreated && (intent.hasExtra(EXTRA_TAB) || intent.action == TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) {
            refreshData()
            showTab(tabFromIntent(intent), animate = true)
        }
    }

    private fun tabFromIntent(intent: Intent?): Int = when {
        intent?.action == TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA -> TAB_VOICES
        else -> intent?.getIntExtra(EXTRA_TAB, TAB_LISTEN) ?: TAB_LISTEN
    }

    override fun onResume() {
        super.onResume()
        // The preferred engine, recents or voice may have changed elsewhere meanwhile.
        if (uiCreated) {
            listenTab.refresh()
            engineTab.updateStatus()
            voicesTab.updateSelection(animate = false)
        }
    }

    override fun onStop() {
        if (uiCreated) preview.stop()
        super.onStop()
    }

    override fun onDestroy() {
        ModelHub.removeListener(hubListener)
        if (uiCreated) preview.release()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, currentTab)
        voiceImportPackId?.let { outState.putString(STATE_VOICE_PACK, it) }
    }

    private fun createUi() {
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.main_root)
        bottomNav = findViewById(R.id.bottom_nav)
        screens = listOf(
            findViewById(R.id.screen_listen),
            findViewById(R.id.screen_voices),
            findViewById(R.id.screen_engine)
        )
        screens.forEach { screen ->
            ViewCompat.setOnApplyWindowInsetsListener(screen) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                view.updatePadding(left = bars.left, top = bars.top + dp(8), right = bars.right)
                insets
            }
        }
        val scrim = findViewById<View>(R.id.status_scrim)
        ViewCompat.setOnApplyWindowInsetsListener(scrim) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).top
            view.layoutParams = view.layoutParams.apply { height = top }
            insets
        }
        listenTab = ListenTab(this, findViewById(R.id.screen_listen))
        voicesTab = VoicesTab(this, findViewById(R.id.screen_voices))
        engineTab = EngineTab(this, findViewById(R.id.screen_engine))
        bottomNav.setOnItemSelectedListener { item ->
            showTab(
                when (item.itemId) {
                    R.id.nav_voices -> TAB_VOICES
                    R.id.nav_engine -> TAB_ENGINE
                    else -> TAB_LISTEN
                }, animate = true
            )
            true
        }
        bottomNav.setOnItemReselectedListener { (screens[currentTab] as? androidx.core.widget.NestedScrollView)?.smoothScrollTo(0, 0) }
        uiCreated = true
    }

    internal fun showTab(tab: Int, animate: Boolean) {
        val target = tab.coerceIn(TAB_LISTEN, TAB_ENGINE)
        val navId = when (target) {
            TAB_VOICES -> R.id.nav_voices
            TAB_ENGINE -> R.id.nav_engine
            else -> R.id.nav_listen
        }
        if (bottomNav.selectedItemId != navId) bottomNav.menu.findItem(navId).isChecked = true
        if (target != TAB_VOICES) preview.stopIfActive()
        val previous = currentTab
        currentTab = target
        screens.forEachIndexed { index, screen ->
            if (index == target) {
                if (animate && previous != target) UiKit.fadeIn(screen, 200) else {
                    screen.alpha = 1f
                    screen.translationY = 0f
                    screen.visibility = View.VISIBLE
                }
            } else {
                screen.animate().cancel()
                screen.visibility = View.GONE
            }
        }
    }

    /** Reloads packs and re-renders every tab, optionally announcing [message]. */
    internal fun refreshData(message: String? = null) {
        packs = ModelPackRepository.list(this)
        // Persist the effective selection, as the former spinners did on their initial selection.
        ModelPackRepository.selectedPack(this)?.let { pack ->
            ModelPackRepository.selectPack(this, pack.id)
            ModelPackRepository.selectedVoiceId(this, pack)?.let { ModelPackRepository.selectVoice(this, pack.id, it) }
        }
        listenTab.refresh()
        voicesTab.render(packs)
        engineTab.render(packs)
        message?.let { showMessage(it) }
    }

    internal fun onVoiceSelected() {
        voicesTab.updateSelection(animate = true)
        engineTab.updateStatus()
    }

    internal fun showMessage(message: String) {
        Snackbar.make(root, message, Snackbar.LENGTH_LONG).setAnchorView(bottomNav).show()
    }

    internal fun isDefaultEngine(): Boolean = runCatching {
        Settings.Secure.getString(contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
    }.getOrNull() == packageName

    internal fun openTtsSettings() {
        runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
            .onFailure { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    internal fun openUrl(url: String, errorMessage: Int) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { showMessage(getString(errorMessage)) }
    }

    internal fun openPackDownloads() = openUrl("$REPOSITORY_URL/releases/latest", R.string.error_open_downloads)

    internal fun openPlayer(recentId: String, autoplay: Boolean) {
        startActivity(
            Intent(this, ReadAloudActivity::class.java)
                .putExtra(ReadAloudActivity.EXTRA_RECENT_ID, recentId)
                .putExtra(ReadAloudActivity.EXTRA_AUTOPLAY, autoplay)
        )
    }

    // ---- Listen: paste text and open file -------------------------------------------------

    internal fun showPasteSheet() {
        val dialog = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.sheet_paste, null)
        val input = content.findViewById<EditText>(R.id.paste_input)
        val count = content.findViewById<TextView>(R.id.paste_count)
        val listen = content.findViewById<MaterialButton>(R.id.paste_listen)
        input.isSaveEnabled = false
        fun update() {
            val length = input.text.length
            count.text = getString(R.string.paste_count, NumberFormat.getIntegerInstance().format(length))
            listen.isEnabled = input.text.isNotBlank()
        }
        fun pasteClipboard() {
            val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            if (text.isNullOrBlank()) {
                if (input.text.isEmpty()) input.requestFocus()
            } else {
                input.setText(text.take(RecentTexts.MAX_CHARS))
                input.setSelection(0)
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = update()
        })
        content.findViewById<View>(R.id.paste_clipboard).setOnClickListener { pasteClipboard() }
        listen.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            val item = RecentTexts.add(this, ReaderText.title(text), text)
            dialog.dismiss()
            openPlayer(item.id, autoplay = true)
        }
        pasteClipboard()
        update()
        dialog.setContentView(content)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }

    internal fun openTextFile() = openDocument(REQUEST_TEXT)

    // ---- Voices and packs ------------------------------------------------------------------

    internal fun confirmModelPackImport() {
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_warning)
            .setTitle(R.string.import_pack_warning_title)
            .setMessage(R.string.import_pack_warning_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.continue_import) { _, _ -> openDocument(REQUEST_MODEL_PACK) }
            .show()
    }

    internal fun startVoiceImport() {
        val candidates = packs
        if (candidates.isEmpty()) {
            showMessage(getString(R.string.status_no_model))
            return
        }
        if (candidates.size == 1) {
            voiceImportPackId = candidates.first().id
            openDocument(REQUEST_VOICE)
            return
        }
        val selected = ModelPackRepository.selectedPack(this)?.id
        var choice = candidates.indexOfFirst { it.id == selected }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clone_choose_pack)
            .setSingleChoiceItems(candidates.map { it.displayName }.toTypedArray(), choice) { _, which -> choice = which }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.continue_import) { _, _ ->
                voiceImportPackId = candidates[choice].id
                openDocument(REQUEST_VOICE)
            }
            .show()
    }

    internal fun confirmDeletePack(pack: ModelPack) {
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_delete)
            .setTitle(R.string.delete_pack_title)
            .setMessage(getString(R.string.delete_pack_message, pack.displayName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ -> deletePack(pack) }
            .show()
    }

    private fun deletePack(pack: ModelPack) {
        preview.stop()
        voicesTab.setBusy(getString(R.string.status_deleting_pack, pack.displayName))
        thread(name = "pockettts-delete-pack") {
            runCatching { ModelPackRepository.deletePack(this, pack) }
                .onSuccess {
                    runOnUiThread {
                        voicesTab.setBusy(null)
                        refreshData(getString(R.string.status_delete_success, pack.displayName))
                    }
                }
                .onFailure { error ->
                    Log.e(TAG, "DELETE_PACK_FAILED pack=${pack.id}", error)
                    runOnUiThread {
                        voicesTab.setBusy(null)
                        showMessage(getString(R.string.status_delete_failed, error.message.orEmpty()))
                    }
                }
        }
    }

    // ---- Engine ------------------------------------------------------------------------------

    internal fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_info)
            .setTitle(R.string.about_title)
            .setMessage(getString(R.string.about_message, BuildConfig.VERSION_NAME, REPOSITORY_URL))
            .setNegativeButton(android.R.string.ok, null)
            .setPositiveButton(R.string.open_repository) { _, _ -> openUrl(REPOSITORY_URL, R.string.error_open_repository) }
            .show()
    }

    internal fun showLicenses() {
        val entries = LICENSES.map { "${it.first} — ${it.second}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.licenses_title)
            .setItems(entries) { _, which ->
                val asset = LICENSES[which].third
                if (asset == null) openUrl("$REPOSITORY_URL/blob/main/THIRD_PARTY_NOTICES.md", R.string.error_open_repository)
                else showLicenseText(LICENSES[which].first, asset)
            }
            .setNegativeButton(android.R.string.ok, null)
            .setPositiveButton(R.string.licenses_all) { _, _ ->
                openUrl("$REPOSITORY_URL/blob/main/THIRD_PARTY_NOTICES.md", R.string.error_open_repository)
            }
            .show()
    }

    private fun showLicenseText(title: String, asset: String) {
        val text = runCatching { assets.open(asset).bufferedReader().use { it.readText() } }.getOrNull() ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ---- Documents ---------------------------------------------------------------------------

    private fun openDocument(requestCode: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            when (requestCode) {
                REQUEST_MODEL_PACK -> {
                    type = "*/*"
                    putExtra(
                        Intent.EXTRA_MIME_TYPES,
                        arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
                    )
                }
                REQUEST_VOICE -> {
                    type = "audio/wav"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/wav", "audio/x-wav", "audio/wave"))
                }
                REQUEST_TEXT -> type = "text/plain"
            }
        }, requestCode)
    }

    @Deprecated("Activity result API is sufficient for this framework-only activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        if (data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) {
            showMessage(getString(R.string.error_untrusted_document))
            return
        }
        if (requestCode == REQUEST_TEXT) {
            importTextFile(uri)
            return
        }
        voicesTab.setBusy(
            if (requestCode == REQUEST_MODEL_PACK) getString(R.string.status_importing_pack)
            else getString(R.string.status_importing_voice)
        )
        showTab(TAB_VOICES, animate = true)
        val voicePack = packs.firstOrNull { it.id == voiceImportPackId } ?: ModelPackRepository.selectedPack(this)
        thread(name = "pockettts-import") {
            runCatching {
                when (requestCode) {
                    REQUEST_MODEL_PACK -> ModelPackRepository.importPack(this, uri).displayName
                    REQUEST_VOICE -> {
                        val pack = voicePack ?: error(getString(R.string.error_no_model_voice))
                        ModelPackRepository.importVoice(this, pack, uri).also {
                            ModelPackRepository.selectPack(this, it.id)
                        }.voices.last().displayName
                    }
                    else -> return@thread
                }
            }.onSuccess { name ->
                runOnUiThread {
                    voicesTab.setBusy(null)
                    refreshData(getString(R.string.status_import_success, name))
                }
            }.onFailure { error ->
                Log.e(TAG, "IMPORT_FAILED", error)
                runOnUiThread {
                    voicesTab.setBusy(null)
                    showMessage(getString(R.string.status_import_failed, error.message.orEmpty()))
                }
            }
        }
    }

    private fun importTextFile(uri: Uri) {
        thread(name = "pockettts-open-text") {
            runCatching {
                val title = ModelPackRepository.queryDisplayName(this, uri).substringBeforeLast('.').trim()
                val bytes = ByteArrayOutputStream()
                var truncated = false
                ModelPackRepository.openSelectedDocument(this, uri, R.string.error_open_text).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        val room = MAX_TEXT_FILE_BYTES - bytes.size()
                        if (read > room) {
                            bytes.write(buffer, 0, room)
                            truncated = true
                            break
                        }
                        bytes.write(buffer, 0, read)
                    }
                }
                val text = ReaderText.decode(bytes.toByteArray(), truncated).trim()
                require(text.any { it.isLetterOrDigit() }) { getString(R.string.error_empty_text) }
                RecentTexts.add(this, title.ifBlank { ReaderText.title(text) }.let { ReaderText.title(it, 60) }, text)
            }.onSuccess { item ->
                runOnUiThread {
                    listenTab.refresh()
                    openPlayer(item.id, autoplay = true)
                }
            }.onFailure { error ->
                Log.e(TAG, "OPEN_TEXT_FAILED", error)
                runOnUiThread { showMessage(getString(R.string.error_open_text_detail, error.message.orEmpty())) }
            }
        }
    }

    // ---- ADB-only diagnostics ----------------------------------------------------------------

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
            setTextAppearance(R.style.TextAppearance_PocketTts_Title)
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

    private fun dp(value: Int): Int = UiKit.dp(this, value.toFloat())

    private data class SelfTestResult(val file: String, val samples: Long, val chunks: Int, val min: Float, val max: Float)
    private data class MultiVoiceTestResult(
        val packId: String,
        val firstVoice: String,
        val secondVoice: String,
        val firstHash: String,
        val secondHash: String,
        val repeatHash: String
    )

    companion object {
        const val EXTRA_TAB = "org.pockettts.android.engine.extra.TAB"
        const val TAB_LISTEN = 0
        const val TAB_VOICES = 1
        const val TAB_ENGINE = 2
        internal const val REPOSITORY_URL = "https://github.com/The-unknown-Shadowman/PocketTTS-Android-Engine"
        private const val TAG = "PocketTTS"
        private const val ACTION_SELF_TEST = "org.pockettts.android.engine.SELF_TEST"
        private const val ACTION_MULTI_VOICE_SELF_TEST = "org.pockettts.android.engine.MULTI_VOICE_SELF_TEST"
        private const val REQUEST_MODEL_PACK = 1001
        private const val REQUEST_VOICE = 1002
        private const val REQUEST_TEXT = 1003
        private const val SAMPLE_RATE = 24_000
        private const val MAX_TEXT_FILE_BYTES = 1_000_000
        private const val STATE_TAB = "tab"
        private const val STATE_VOICE_PACK = "voice_pack"

        /** Component, license, bundled license asset (null: see THIRD_PARTY_NOTICES.md). */
        private val LICENSES = listOf(
            Triple("Inter", "SIL Open Font License 1.1", "licenses/Inter-OFL.txt"),
            Triple("Literata", "SIL Open Font License 1.1", "licenses/Literata-OFL.txt"),
            Triple("Sonic", "Apache-2.0", "licenses/sonic-LICENSE.txt"),
            Triple("Pocket TTS", "MIT / CC BY 4.0", null),
            Triple("PocketTTS.cpp", "MIT", null),
            Triple("ONNX Runtime", "MIT", null),
            Triple("SentencePiece", "Apache-2.0", null),
            Triple("dr_libs", "MIT-0 / Public domain", null),
            Triple("AndroidX, Material Components", "Apache-2.0", null),
            Triple("Kotlin", "Apache-2.0", null)
        )
    }
}
