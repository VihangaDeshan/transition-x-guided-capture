// SPDX-License-Identifier: Apache-2.0
// Audio guidance (TDD v1.0 section 6.6, "Don't Look Now", CHI 2024): the user can keep their eyes
// on the room. Uses the phone's own TextToSpeech engine - no network, no extra dependency. If the
// phone has no voice for the requested language (Sinhala often missing), English is spoken.
package lk.transitionx.capture.android

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class Speech(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile private var ready = false

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (!ready) Log.w(TAG, "TextToSpeech unavailable: $status")
    }

    /** Speaks [text] in [languageTag], or [fallbackEnglish] in English if that voice is missing. */
    fun speak(text: String, languageTag: String, fallbackEnglish: String?): Boolean {
        if (!ready) return false
        return try {
            val wanted = Locale.forLanguageTag(languageTag)
            val available = tts.isLanguageAvailable(wanted) >= TextToSpeech.LANG_AVAILABLE
            val locale = if (available) wanted else Locale.ENGLISH
            val say = if (available) text else (fallbackEnglish ?: text)
            tts.language = locale
            tts.speak(say.take(MAX_CHARS), TextToSpeech.QUEUE_FLUSH, null, "guided-capture")
            true
        } catch (e: Exception) {
            Log.w(TAG, "speak failed", e)
            false
        }
    }

    fun stop() {
        try { tts.stop() } catch (_: Exception) {}
    }

    fun shutdown() {
        try { tts.shutdown() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "GuidedCaptureSpeech"
        private const val MAX_CHARS = 200
    }
}
