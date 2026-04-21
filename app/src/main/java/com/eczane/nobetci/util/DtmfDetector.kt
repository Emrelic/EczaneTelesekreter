package com.eczane.nobetci.util

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * DTMF (Dual-Tone Multi-Frequency) tuş algılayıcı.
 * Goertzel algoritması kullanarak telefon tuş seslerini algılar.
 *
 * Arayan kişi 1'e veya 2'ye bastığında hoparlörden gelen sesi
 * mikrofonla algılayarak hangi tuşa basıldığını tespit eder.
 */
class DtmfDetector(
    private val onKeyDetected: (Char) -> Unit
) {

    companion object {
        private const val TAG = "DtmfDetector"
        private const val SAMPLE_RATE = 8000
        private const val BLOCK_SIZE = 205 // ~25ms blok (Goertzel için ideal)

        // DTMF satır frekansları
        private val ROW_FREQUENCIES = doubleArrayOf(697.0, 770.0, 852.0, 941.0)
        // DTMF sütun frekansları
        private val COL_FREQUENCIES = doubleArrayOf(1209.0, 1336.0, 1477.0, 1633.0)

        // DTMF tuş haritası [satır][sütun]
        private val DTMF_MAP = arrayOf(
            charArrayOf('1', '2', '3', 'A'),
            charArrayOf('4', '5', '6', 'B'),
            charArrayOf('7', '8', '9', 'C'),
            charArrayOf('*', '0', '#', 'D')
        )

        // Algılama eşik değeri
        private const val DETECTION_THRESHOLD = 1000.0
        // Aynı tuşun tekrar algılanması için minimum süre (ms)
        private const val DEBOUNCE_MS = 300L
    }

    private var audioRecord: AudioRecord? = null
    private var isListening = false
    private var listenerThread: Thread? = null
    private var lastDetectedKey: Char = ' '
    private var lastDetectionTime: Long = 0

    /**
     * DTMF dinlemeyi başlat
     */
    fun startListening() {
        if (isListening) return

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize.coerceAtLeast(BLOCK_SIZE * 2)
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord başlatılamadı")
                return
            }

            audioRecord?.startRecording()
            isListening = true

            listenerThread = Thread {
                Log.d(TAG, "DTMF dinleme başladı")
                val buffer = ShortArray(BLOCK_SIZE)

                while (isListening) {
                    val read = audioRecord?.read(buffer, 0, BLOCK_SIZE) ?: 0
                    if (read > 0) {
                        processBuffer(buffer, read)
                    }
                }
                Log.d(TAG, "DTMF dinleme durdu")
            }.apply {
                name = "DtmfDetectorThread"
                isDaemon = true
                start()
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "Mikrofon izni yok", e)
        } catch (e: Exception) {
            Log.e(TAG, "DTMF dinleme başlatılamadı", e)
        }
    }

    /**
     * DTMF dinlemeyi durdur
     */
    fun stopListening() {
        isListening = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord durdurma hatası", e)
        }
        listenerThread = null
    }

    /**
     * Ses verisini işle ve DTMF tonu ara
     */
    private fun processBuffer(buffer: ShortArray, length: Int) {
        // Double dizisine çevir
        val samples = DoubleArray(length) { buffer[it].toDouble() }

        // Enerji kontrolü - çok sessizse atla
        val energy = samples.sumOf { it * it } / length
        if (energy < 10000) return // Sessizlik eşiği

        // Her DTMF frekansı için Goertzel hesapla
        val rowMagnitudes = ROW_FREQUENCIES.map { freq ->
            goertzel(samples, length, freq)
        }
        val colMagnitudes = COL_FREQUENCIES.map { freq ->
            goertzel(samples, length, freq)
        }

        // En güçlü satır ve sütun frekansını bul
        val maxRowIdx = rowMagnitudes.indices.maxByOrNull { rowMagnitudes[it] } ?: return
        val maxColIdx = colMagnitudes.indices.maxByOrNull { colMagnitudes[it] } ?: return

        val maxRowMag = rowMagnitudes[maxRowIdx]
        val maxColMag = colMagnitudes[maxColIdx]

        // Eşik kontrolü - her iki frekans da yeterince güçlü olmalı
        if (maxRowMag > DETECTION_THRESHOLD && maxColMag > DETECTION_THRESHOLD) {
            val detectedKey = DTMF_MAP[maxRowIdx][maxColIdx]

            // Debounce - aynı tuşun çok hızlı algılanmasını önle
            val now = System.currentTimeMillis()
            if (detectedKey != lastDetectedKey || (now - lastDetectionTime) > DEBOUNCE_MS) {
                lastDetectedKey = detectedKey
                lastDetectionTime = now

                Log.d(TAG, "DTMF algılandı: $detectedKey (row=${maxRowMag.toInt()}, col=${maxColMag.toInt()})")
                onKeyDetected(detectedKey)
            }
        }
    }

    /**
     * Goertzel algoritması - belirli bir frekanstaki enerjiyi hesaplar.
     * FFT'den daha verimli çünkü sadece istenen frekansı hesaplar.
     */
    private fun goertzel(samples: DoubleArray, length: Int, targetFreq: Double): Double {
        val k = (0.5 + (length * targetFreq / SAMPLE_RATE)).toInt()
        val omega = 2.0 * Math.PI * k / length
        val coeff = 2.0 * cos(omega)

        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0

        for (i in 0 until length) {
            s0 = samples[i] + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }

        // Büyüklük (magnitude)
        return sqrt(s1 * s1 + s2 * s2 - coeff * s1 * s2)
    }
}
