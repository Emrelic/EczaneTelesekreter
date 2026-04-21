package com.eczane.nobetci.util

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * Text-to-Speech yöneticisi.
 * Yazılan metni Türkçe yapay zeka sesiyle okur ve ses dosyası olarak kaydeder.
 */
class TtsManager(
    private val context: Context,
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit
) {

    companion object {
        private const val TAG = "TtsManager"
        const val TTS_FILE_NAME = "tts_mesaj.wav"
    }

    private var tts: TextToSpeech? = null
    private var isReady = false

    /** TTS motorunu başlat */
    fun initialize() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("tr", "TR"))
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Log.e(TAG, "Türkçe TTS desteklenmiyor")
                    onError("Türkçe ses desteği bulunamadı. Lütfen telefon ayarlarından Türkçe TTS yükleyin.")
                } else {
                    isReady = true
                    // Ses ayarları
                    tts?.setPitch(1.0f)      // Normal ton
                    tts?.setSpeechRate(0.9f)  // Biraz yavaş (anlaşılır olması için)
                    Log.d(TAG, "TTS hazır - Türkçe")
                    onReady()
                }
            } else {
                Log.e(TAG, "TTS başlatılamadı: $status")
                onError("Ses motoru başlatılamadı")
            }
        }
    }

    /** Metni sesli oku (hoparlörden) */
    fun speak(text: String) {
        if (!isReady) {
            onError("Ses motoru hazır değil")
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "speak_id")
    }

    /** Konuşmayı durdur */
    fun stopSpeaking() {
        tts?.stop()
    }

    /**
     * Metni ses dosyası olarak kaydet.
     * @param text Okunacak metin
     * @param onComplete Kayıt tamamlandığında çağrılır (dosya yolu ile)
     * @param onSaveError Hata olursa çağrılır
     */
    fun saveToFile(
        text: String,
        onComplete: (String) -> Unit,
        onSaveError: (String) -> Unit
    ) {
        if (!isReady) {
            onSaveError("Ses motoru hazır değil")
            return
        }

        val filePath = "${context.filesDir.absolutePath}/$TTS_FILE_NAME"
        val file = File(filePath)

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS dosyaya yazma başladı")
            }

            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS dosyaya yazma tamamlandı: $filePath")
                onComplete(filePath)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS dosyaya yazma hatası")
                onSaveError("Ses dosyası oluşturulamadı")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS dosyaya yazma hatası: $errorCode")
                onSaveError("Ses dosyası oluşturulamadı (kod: $errorCode)")
            }
        })

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "save_id")
        }

        val result = tts?.synthesizeToFile(text, params, file, "save_id")
        if (result != TextToSpeech.SUCCESS) {
            onSaveError("Ses sentezi başlatılamadı")
        }
    }

    /** Konuşma hızını ayarla (0.5 - 2.0 arası, 1.0 normal) */
    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    }

    /** Ses tonunu ayarla (0.5 - 2.0 arası, 1.0 normal) */
    fun setPitch(pitch: Float) {
        tts?.setPitch(pitch.coerceIn(0.5f, 2.0f))
    }

    /** Temizlik */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
