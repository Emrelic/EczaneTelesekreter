package com.eczane.nobetci.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GSM gorusmesi sirasinda arayan kisiye ses dinletir.
 *
 * Hoparlor ve mikrofon KULLANILMAZ.
 * Sesi elektronik olarak dogrudan ses yoluna (voice call stream) enjekte eder.
 *
 * 3 paralel AudioTrack stratejisi:
 * 1) STREAM_VOICE_CALL (deprecated constructor) -> bazi chipsetlerde dogrudan uplink
 * 2) USAGE_VOICE_COMMUNICATION + setPreferredDevice(TELEPHONY/EARPIECE)
 * 3) USAGE_VOICE_COMMUNICATION_SIGNALLING (DTMF yolu - her zaman uplink'e gider)
 *
 * Hepsi ayni anda calisir, en az biri basarili olur.
 */
class CallAudioPlayer(private val context: Context) {

    companion object {
        private const val TAG = "CallAudioPlayer"
        private const val TIMEOUT_US = 10000L
        private const val SAMPLE_RATE = 8000  // Telefon sebekesi standardi
    }

    private var track1: AudioTrack? = null
    private var track2: AudioTrack? = null
    private var track3: AudioTrack? = null
    private var thread1: Thread? = null
    private var thread2: Thread? = null
    private var thread3: Thread? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var originalMode = AudioManager.MODE_NORMAL

    @Volatile
    private var isPlaying = false

    fun play(
        filePath: String,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        stop()
        isPlaying = true

        originalMode = audioManager.mode
        Log.d(TAG, "=== SEBEKE ENJEKSIYON BASLATILIYOR ===")
        Log.d(TAG, "Dosya: $filePath, AudioMode=$originalMode")

        // Hoparloru KAPAT - ses sadece elektronik yoldan gitmeli
        try {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        } catch (_: Exception) {}

        // VOICE_CALL volume MAX
        try {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, max, 0)
            Log.d(TAG, "VOICE_CALL ses=$max/$max")
        } catch (_: Exception) {}

        // Cikis cihazlarini logla
        logDevices()

        // 3 strateji paralel baslat
        thread1 = startStrategy1(filePath, onComplete, onError)
        thread2 = startStrategy2(filePath)
        thread3 = startStrategy3(filePath)
    }

    private fun logDevices() {
        try {
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (d in outputs) {
                Log.d(TAG, "  Cikis: ${deviceName(d.type)} id=${d.id}")
            }
        } catch (_: Exception) {}
    }

    // ==========================================
    // STRATEJI 1: Deprecated STREAM_VOICE_CALL
    // Samsung/Qualcomm chipsetlerde uplink'e gider
    // ==========================================
    private fun startStrategy1(filePath: String, onComplete: () -> Unit, onError: (String) -> Unit): Thread {
        return Thread {
            try {
                val bufSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                @Suppress("DEPRECATION")
                val track = AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize * 4,
                    AudioTrack.MODE_STREAM
                )
                track1 = track

                // Telephony cihazina yonlendir
                routeToDevice(track, AudioDeviceInfo.TYPE_TELEPHONY, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)

                track.play()
                Log.d(TAG, "[S1] STREAM_VOICE_CALL AudioTrack BASLATILDI")

                decodeAndWrite(filePath, track)

                track.stop(); track.release()
                track1 = null
                Log.d(TAG, "[S1] TAMAMLANDI")

                if (isPlaying) {
                    isPlaying = false
                    onComplete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "[S1] HATA: ${e.message}")
                onError(e.message ?: "S1 hata")
            }
        }.apply { name = "VoiceCallTrack"; isDaemon = true; start() }
    }

    // ==========================================
    // STRATEJI 2: USAGE_VOICE_COMMUNICATION
    // VoIP yolu - MODE_IN_COMMUNICATION ile
    // ==========================================
    private fun startStrategy2(filePath: String): Thread {
        return Thread {
            try {
                // Kisa gecikme - strateji 1 once baslasin
                Thread.sleep(100)

                val bufSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufSize * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track2 = track

                routeToDevice(track, AudioDeviceInfo.TYPE_TELEPHONY, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)

                track.play()
                Log.d(TAG, "[S2] VOICE_COMMUNICATION AudioTrack BASLATILDI")

                decodeAndWrite(filePath, track)

                track.stop(); track.release()
                track2 = null
                Log.d(TAG, "[S2] TAMAMLANDI")
            } catch (e: Exception) {
                Log.e(TAG, "[S2] HATA: ${e.message}")
            }
        }.apply { name = "VoipTrack"; isDaemon = true; start() }
    }

    // ==========================================
    // STRATEJI 3: USAGE_VOICE_COMMUNICATION_SIGNALLING
    // DTMF/sinyal yolu - HER ZAMAN uplink'e gider
    // (DTMF tonlari bu yoldan gider, ses de gidebilir)
    // ==========================================
    private fun startStrategy3(filePath: String): Thread {
        return Thread {
            try {
                Thread.sleep(200)

                val bufSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufSize * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track3 = track

                routeToDevice(track, AudioDeviceInfo.TYPE_TELEPHONY, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)

                track.play()
                Log.d(TAG, "[S3] VOICE_COMMUNICATION_SIGNALLING AudioTrack BASLATILDI")

                decodeAndWrite(filePath, track)

                track.stop(); track.release()
                track3 = null
                Log.d(TAG, "[S3] TAMAMLANDI")
            } catch (e: Exception) {
                Log.e(TAG, "[S3] HATA: ${e.message}")
            }
        }.apply { name = "SignalTrack"; isDaemon = true; start() }
    }

    // ==========================================
    // YARDIMCI
    // ==========================================

    private fun routeToDevice(track: AudioTrack, primaryType: Int, fallbackType: Int) {
        try {
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val primary = outputs.firstOrNull { it.type == primaryType }
            val fallback = outputs.firstOrNull { it.type == fallbackType }
            val target = primary ?: fallback
            if (target != null) {
                track.setPreferredDevice(target)
                Log.d(TAG, "  -> ${deviceName(target.type)} yonlendirildi")
            }
        } catch (_: Exception) {}
    }

    private fun decodeAndWrite(filePath: String, track: AudioTrack) {
        val extractor = MediaExtractor()
        try {
            if (filePath.startsWith("content://")) {
                extractor.setDataSource(context, Uri.parse(filePath), null)
            } else {
                extractor.setDataSource(filePath)
            }

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    trackIndex = i; format = f; break
                }
            }
            if (trackIndex == -1 || format == null) return
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcCh = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false

            while (isPlaying) {
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

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outIdx >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        codec.releaseOutputBuffer(outIdx, false)
                        break
                    }
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        val pcm = ShortArray(bufferInfo.size / 2)
                        outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
                        outBuf.clear()

                        val resampled = resampleToMono8k(pcm, srcRate, srcCh)

                        // Sesi yukseltelim (x3 amplify)
                        val amplified = ShortArray(resampled.size) { i ->
                            (resampled[i].toInt() * 3)
                                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        }

                        val bytes = ByteArray(amplified.size * 2)
                        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer().put(amplified)

                        track.write(bytes, 0, bytes.size)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }

            codec.stop()
            codec.release()
        } finally {
            extractor.release()
        }
    }

    private fun resampleToMono8k(pcm: ShortArray, srcRate: Int, srcChannels: Int): ShortArray {
        val mono = if (srcChannels > 1) {
            ShortArray(pcm.size / srcChannels) { i ->
                var sum = 0L
                for (ch in 0 until srcChannels) {
                    val idx = i * srcChannels + ch
                    if (idx < pcm.size) sum += pcm[idx]
                }
                (sum / srcChannels).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        } else pcm

        if (srcRate == SAMPLE_RATE) return mono

        val ratio = srcRate.toDouble() / SAMPLE_RATE
        val outLen = (mono.size / ratio).toInt()
        if (outLen <= 0) return ShortArray(0)

        return ShortArray(outLen) { i ->
            val srcPos = i * ratio
            val idx = srcPos.toInt()
            val frac = srcPos - idx
            if (idx + 1 < mono.size) {
                (mono[idx] * (1.0 - frac) + mono[idx + 1] * frac).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            } else if (idx < mono.size) mono[idx]
            else 0
        }
    }

    fun stop() {
        isPlaying = false

        for (t in listOf(track1, track2, track3)) {
            try { t?.stop() } catch (_: Exception) {}
            try { t?.release() } catch (_: Exception) {}
        }
        track1 = null; track2 = null; track3 = null
        thread1 = null; thread2 = null; thread3 = null

        try { audioManager.mode = originalMode } catch (_: Exception) {}
        Log.d(TAG, "Ses ayarlari geri yuklendi")
    }

    fun isCurrentlyPlaying(): Boolean = isPlaying

    private fun deviceName(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
        else -> "TYPE_$type"
    }
}
