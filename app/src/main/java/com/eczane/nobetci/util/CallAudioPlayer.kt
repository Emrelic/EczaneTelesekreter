package com.eczane.nobetci.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.util.Log

/**
 * Ses dosyasini telefon gorusmesi sirasinda arayan kisiye dinletir.
 *
 * Yontem: AudioTrack ile STREAM_VOICE_CALL kanalina PCM veri yazar.
 * Bu kanal, gorusme sirasinda arayan kisinin duydugu ses kanalidir.
 * (ToneGenerator DTMF tonlarini ayni kanaldan gonderir ve arayan duyar)
 *
 * Eko filtresi devre disi birakilir, boylece ses engellenmez.
 *
 * AudioManager modu DEGISTIRILMEZ - sistem MODE_IN_CALL modunda kalir.
 */
class CallAudioPlayer(private val context: Context) {

    companion object {
        private const val TAG = "CallAudioPlayer"
        private const val TIMEOUT_US = 10000L
    }

    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    @Volatile
    private var isPlaying = false

    /**
     * Ses dosyasini gorusme kanalinda cal.
     */
    fun play(
        filePath: String,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        stop()

        playbackThread = Thread {
            try {
                playInternal(filePath, onComplete, onError)
            } catch (e: Exception) {
                Log.e(TAG, "Oynatma hatasi", e)
                onError(e.message ?: "Hata")
            }
        }.apply {
            name = "CallAudioThread"
            isDaemon = true
            start()
        }
    }

    @Suppress("DEPRECATION")
    private fun playInternal(
        filePath: String,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        val extractor = MediaExtractor()

        try {
            // Ses dosyasini ac
            if (filePath.startsWith("content://")) {
                extractor.setDataSource(context, Uri.parse(filePath), null)
            } else {
                extractor.setDataSource(filePath)
            }

            // Ses track'ini bul
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }

            if (trackIndex == -1 || format == null) {
                onError("Ses kanali bulunamadi")
                return
            }

            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            Log.d(TAG, "Format: $mime, ${sampleRate}Hz, ${channelCount}ch")

            // Codec ile decode
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val channelConfig = if (channelCount == 1)
                AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO

            val bufSize = AudioTrack.getMinBufferSize(
                sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT
            )

            // ============================================
            // YONTEM 1: AudioTrack + STREAM_VOICE_CALL
            // ToneGenerator ile ayni kanal - arayan duyar
            // ============================================
            audioTrack = AudioTrack(
                AudioManager.STREAM_VOICE_CALL,  // Gorusme ses kanali
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize * 4,
                AudioTrack.MODE_STREAM
            )

            val sessionId = audioTrack!!.audioSessionId

            // ============================================
            // EKO FILTRESINI DEVRE DISI BIRAK
            // Yoksa telefon hoparlor sesini filtreler
            // ============================================
            disableEchoCancellation(sessionId)

            audioTrack?.play()
            isPlaying = true
            Log.d(TAG, ">>> VOICE_CALL kanalina ses yaziliyor (session=$sessionId)")

            // Decode ve yaz dongusu
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false

            while (isPlaying) {
                // Compressed -> Codec
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx) ?: continue
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Decoded PCM -> AudioTrack (VOICE_CALL)
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outIdx >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        codec.releaseOutputBuffer(outIdx, false)
                        break
                    }
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        val pcm = ByteArray(bufferInfo.size)
                        outBuf.get(pcm)
                        outBuf.clear()
                        // VOICE_CALL kanalina yaz -> arayan kisiye gider
                        audioTrack?.write(pcm, 0, pcm.size)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }

            // Temizlik
            codec.stop()
            codec.release()
            extractor.release()
            releaseAudioEffects()
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            isPlaying = false

            Log.d(TAG, "Oynatma tamamlandi")
            onComplete()

        } catch (e: Exception) {
            Log.e(TAG, "Hata", e)
            extractor.release()
            releaseAudioEffects()
            isPlaying = false
            onError(e.message ?: "Hata")
        }
    }

    /**
     * Eko filtresini ve gurultu bastiriciyi devre disi birak.
     * Boylece hoparlorden/audiotrack'ten gelen ses
     * mikrofon tarafindan filtrelenmez.
     */
    private fun disableEchoCancellation(audioSessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = false
                    Log.d(TAG, "Eko filtresi DEVRE DISI")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Eko filtresi devre disi birakilamadi", e)
        }

        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = false
                    Log.d(TAG, "Gurultu bastiricisi DEVRE DISI")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gurultu bastiricisi devre disi birakilamadi", e)
        }
    }

    private fun releaseAudioEffects() {
        try { echoCanceler?.release() } catch (_: Exception) {}
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        echoCanceler = null
        noiseSuppressor = null
    }

    fun stop() {
        isPlaying = false
        releaseAudioEffects()
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        playbackThread = null
    }

    fun isCurrentlyPlaying(): Boolean = isPlaying
}
