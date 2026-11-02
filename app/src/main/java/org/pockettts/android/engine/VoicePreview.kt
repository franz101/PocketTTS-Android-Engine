package org.pockettts.android.engine

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/**
 * Speaks a short sample for a voice card through Android's TextToSpeech bound to this
 * engine, exactly as other apps would hear it.
 */
internal class VoicePreview(
    private val context: Context,
    private val onState: (String?, State) -> Unit
) {
    enum class State { IDLE, LOADING, PLAYING }

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: VoiceEntry? = null
    private var active: String? = null
    private var utterance: String? = null
    private var counter = 0

    fun toggle(entry: VoiceEntry) {
        if (active == entry.name) stop() else play(entry)
    }

    private fun play(entry: VoiceEntry) {
        stop()
        active = entry.name
        onState(entry.name, State.LOADING)
        val client = tts ?: create()
        if (ready) speak(client, entry) else pending = entry
    }

    private fun create(): TextToSpeech {
        val client = TextToSpeech(context, { status ->
            main.post {
                ready = status == TextToSpeech.SUCCESS
                val next = pending
                pending = null
                val current = tts
                if (next != null && current != null) {
                    if (ready) speak(current, next) else finish(next.name)
                }
            }
        }, context.packageName)
        client.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        client.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = post(utteranceId) { onState(it, State.PLAYING) }
            override fun onDone(utteranceId: String?) = post(utteranceId) { finish(it) }
            @Deprecated("Required by Android's TTS listener")
            override fun onError(utteranceId: String?) = post(utteranceId) { finish(it) }
            override fun onError(utteranceId: String?, errorCode: Int) = post(utteranceId) { finish(it) }
        })
        tts = client
        return client
    }

    private fun post(utteranceId: String?, action: (String) -> Unit) {
        main.post {
            val name = active
            if (utteranceId != null && utteranceId == utterance && name != null) action(name)
        }
    }

    private fun speak(client: TextToSpeech, entry: VoiceEntry) {
        val voice = runCatching { client.voices }.getOrNull()?.firstOrNull { it.name == entry.name }
        if (voice == null || client.setVoice(voice) != TextToSpeech.SUCCESS) {
            finish(entry.name)
            return
        }
        client.setSpeechRate(1f)
        val text = UiKit.localized(context, entry.pack.locale)
            .getString(R.string.voice_preview_sample, entry.voice.displayName)
        val id = "preview-${++counter}"
        utterance = id
        if (client.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id) != TextToSpeech.SUCCESS) finish(entry.name)
    }

    private fun finish(name: String) {
        if (active == name) {
            active = null
            utterance = null
        }
        onState(name, State.IDLE)
    }

    fun stopIfActive() {
        if (active != null) stop()
    }

    fun stop() {
        val name = active ?: return
        pending = null
        utterance = null
        tts?.stop()
        active = null
        onState(name, State.IDLE)
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
