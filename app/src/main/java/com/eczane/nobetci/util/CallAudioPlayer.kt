package com.eczane.nobetci.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Ses dosyasını doğrudan telefon görüşmesi kanalına (uplink) yönlendiren oynatıcı.
 *
 * Normal MediaPlayer hoparlörden çalar ve arayan kişi duyamaz.
 * Bu sınıf AudioTrack + USAGE_VOICE_COMMUNICATION kullanarak
 * sesi doğrudan arayan kişinin duyacağı kanala yazar.
 *
 * Çalışma prensibi:
 * 1. MediaExtractor + MediaCodec ile ses dosyasını PCM'e decode eder
 * 2. AudioTrack (VOICE_COMMUNICATION) ile PCM veriyi arama kanalına yazar
 * 3. Android ses sistemi bu veriyi arama uplink'ine karıştırır
 */
class CallAudioPlayer(private val context: Context) {

    companion object {
        private const val TAG = "CallAudioPlayer"
        private const val SAMPLE_RATE = 16000 // 16kHz - telefon kalitesi
        private const val TIMEOUT_US = 10000L
    }

    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    @Volatile
    private var isPlaying = false
    private var onComplete: (() -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    /**
     * Ses dosyasını arama kanalından çal.
     * Arayan kişi bu sesi doğrudan duyar.
     *
     * @param filePath Ses dosyası yolu (WAV, M4A, MP3, vb.)
     * @param onComplete Çalma bittiğinde
     * @param onError Hata olduğunda
     */
    fun play(
        filePath: String,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        this.onComplete = onComplete
        this.onError = onError

        if (isPlaying) {
            stop()
        }

        playbackThread = Thread {
            try {
                playInternal(filePath)
            } catch (e: Exception) {
                Log.e(TAG, "Oynatma hatasi", e)
                onError(e.message ?: "Bilinmeyen hata")
            }
        }.apply {
            name = "CallAudioPlayerThread"
            isDaemon = true
            start()
        }
    }

    private fun playInternal(filePath: String) {
        val extractor = MediaExtractor()

        try {
            // Dosyayı aç
            if (filePath.startsWith("content://")) {
                extractor.setDataSource(context, Uri.parse(filePath), null)
            } else {
                extractor.setDataSource(filePath)
            }

            // Ses track'ini bul
            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || inputFormat == null) {
                onError?.invoke("Ses dosyasinda ses kanali bulunamadi")
                return
            }

            extractor.selectTrack(audioTrackIndex)

            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: ""
            val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            Log.d(TAG, "Ses format: mime=$mime, rate=$sampleRate, channels=$channelCount")

            // MediaCodec ile decode et
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            // AudioTrack oluştur - VOICE_COMMUNICATION ile arama kanalına yaz
            val channelConfig = if (channelCount == 1)
                AudioFormat.CHANNEL_OUT_MONO
            else
                AudioFormat.CHANNEL_OUT_STEREO

            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            isPlaying = true

            Log.d(TAG, "AudioTrack baslatildi - ses arama kanalina yonlendirilecek")

            // Decode ve oynat döngüsü
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false

            while (isPlaying) {
                // Input: Compressed veriyi codec'e ver
                if (!inputDone) {
                    val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)

                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputBufferIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inputBufferIndex, 0, sampleSize,
                                extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                // Output: Decoded PCM veriyi AudioTrack'e yaz
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputBufferIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        // Dosya bitti
                        Log.d(TAG, "Ses dosyasi bitti")
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                        break
                    }

                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val pcmData = ByteArray(bufferInfo.size)
                        outputBuffer.get(pcmData)
                        outputBuffer.clear()

                        // PCM veriyi arama kanalına yaz
                        audioTrack?.write(pcmData, 0, pcmData.size)
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                }
            }

            // Temizlik
            codec.stop()
            codec.release()
            extractor.release()

            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null

            isPlaying = false

            if (isPlaying) {
                // Kullanıcı durdurdu
                Log.d(TAG, "Oynatma kullanici tarafindan durduruldu")
            } else {
                Log.d(TAG, "Oynatma tamamlandi")
                onComplete?.invoke()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ses oynatma hatasi", e)
            extractor.release()
            isPlaying = false
            onError?.invoke(e.message ?: "Ses oynatma hatasi")
        }
    }

    /**
     * Oynatmayı durdur
     */
    fun stop() {
        isPlaying = false
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack durdurma hatasi", e)
        }
        playbackThread = null
    }

    /**
     * Çalıyor mu?
     */
    fun isCurrentlyPlaying(): Boolean = isPlaying
}
