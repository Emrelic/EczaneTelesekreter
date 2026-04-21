package com.eczane.nobetci.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.eczane.nobetci.R
import com.eczane.nobetci.ui.MainActivity
import com.eczane.nobetci.util.DtmfDetector
import com.eczane.nobetci.util.PrefsManager
import com.eczane.nobetci.util.WhatsAppHelper

/**
 * Eczane Telesekreter IVR Servisi.
 *
 * Ses iletim yontemi:
 * - Arama cevaplanir
 * - Hoparlor (speakerphone) acilir
 * - Ses mesaji STREAM_MUSIC kanalindan hoparlore verilir
 * - Telefonun mikrofonu hoparlorden gelen sesi alir
 * - Mikrofon sesi sebekeye (arayan kisiye) iletir
 *
 * NOT: STREAM_MUSIC kullanilmasinin sebebi:
 * STREAM_VOICE_CALL kullanildiginda telefonun eko filtresi
 * hoparlorden gelen sesi mikrofon sinyalinden cikariyor.
 * STREAM_MUSIC eko filtresinden etkilenmez.
 */
class AutoAnswerService : Service() {

    companion object {
        private const val TAG = "AutoAnswerService"
        const val ACTION_INCOMING_CALL = "com.eczane.nobetci.INCOMING_CALL"
        const val ACTION_CALL_ENDED = "com.eczane.nobetci.CALL_ENDED"
        const val ACTION_SEND_WHATSAPP = "com.eczane.nobetci.SEND_WHATSAPP"
        const val EXTRA_PHONE_NUMBER = "phone_number"

        private const val NOTIFICATION_CHANNEL_ID = "nobetci_eczane_channel"
        private const val NOTIFICATION_CHANNEL_ALERT = "nobetci_eczane_alert"
        private const val NOTIFICATION_CHANNEL_WHATSAPP = "nobetci_eczane_whatsapp"
        private const val NOTIFICATION_ID = 1001
        private const val ALERT_NOTIFICATION_ID = 1002
        private const val WHATSAPP_NOTIFICATION_ID = 1003

        private const val ANSWER_DELAY_MS = 1500L
        // Arama cevaplanip hoparlor acilmadan once bekleme
        // Cok onemli: Arama tam baglanmadan hoparlor acilmaz
        private const val SPEAKER_DELAY_MS = 2000L
        // Hoparlor acildiktan sonra mesaj baslamadan once bekleme
        private const val PLAY_DELAY_MS = 500L
        private const val AUTO_CONNECT_TIMEOUT_MS = 30000L
    }

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager
    private lateinit var prefs: PrefsManager
    private var currentCallerNumber: String = ""
    private var dtmfDetector: DtmfDetector? = null
    private var whatsappSent = false
    private var isConnectedToPharmacist = false
    private var isMessagePlaying = false

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        prefs = PrefsManager(this)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INCOMING_CALL -> {
                val phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: "Bilinmeyen"
                currentCallerNumber = phoneNumber
                whatsappSent = false
                isConnectedToPharmacist = false
                isMessagePlaying = false
                Log.d(TAG, "Gelen arama: $phoneNumber")

                startForeground(NOTIFICATION_ID, createForegroundNotification(phoneNumber))

                handler.postDelayed({ answerCall() }, ANSWER_DELAY_MS)
            }

            ACTION_CALL_ENDED -> {
                Log.d(TAG, "Arama sona erdi")
                cleanup()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_SEND_WHATSAPP -> {
                val phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: ""
                if (phoneNumber.isNotEmpty()) {
                    WhatsAppHelper.sendLocationMessage(this, phoneNumber, prefs)
                    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                        .cancel(WHATSAPP_NOTIFICATION_ID)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ========================================
    // ARAMA AKISI
    // ========================================

    /**
     * Adim 1: Aramayi cevapla
     */
    private fun answerCall() {
        try {
            val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tm.acceptRingingCall()
            Log.d(TAG, "Arama cevaplandi, hoparlor bekleniyor...")

            // Adim 2: Aramanin tam baglanmasini bekle, sonra hoparloru ac
            handler.postDelayed({ enableSpeaker() }, SPEAKER_DELAY_MS)

        } catch (e: Exception) {
            Log.e(TAG, "Arama cevaplanamadi", e)
        }
    }

    /**
     * Adim 2: Hoparloru ac
     */
    private fun enableSpeaker() {
        try {
            // Hoparloru ac - arayan kisinin sesini hoparlorden duymak
            // ve bizim ses mesajimizi mikrofona yansitmak icin
            audioManager.isSpeakerphoneOn = true

            // STREAM_MUSIC ses seviyesini MAKSIMUMA cek
            // Bu kanal eko filtresinden etkilenmez
            val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)

            // VOICE_CALL ses seviyesini de yukselt (arayan kisiyi duymak icin)
            val maxVoice = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVoice, 0)

            Log.d(TAG, "Hoparlor ACIK, ses MAKSIMUM")

            // Adim 3: Kisa bir bekleme sonra mesaji cal
            handler.postDelayed({ playMessage() }, PLAY_DELAY_MS)

            // WhatsApp konum gonder
            sendWhatsAppLocationAuto()

        } catch (e: Exception) {
            Log.e(TAG, "Hoparlor hatasi", e)
        }
    }

    /**
     * Adim 3: Ses mesajini cal
     * STREAM_MUSIC kanalindan hoparlore ses verir.
     * Hoparlorden cikan ses, telefonun mikrofonu tarafindan alinir
     * ve sebeke uzerinden arayan kisiye iletilir.
     */
    private fun playMessage() {
        // Onceki oynatmayi temizle
        stopCurrentPlayback()

        // DTMF algilamayi baslat
        startDtmfDetection()

        try {
            if (prefs.usesCustomAudio && prefs.audioFilePath != null) {
                val filePath = prefs.audioFilePath!!
                Log.d(TAG, "Ozel ses: $filePath")

                mediaPlayer = MediaPlayer().apply {
                    // STREAM_MUSIC kullan - eko filtresinden etkilenmez!
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    if (filePath.startsWith("content://")) {
                        setDataSource(this@AutoAnswerService, Uri.parse(filePath))
                    } else {
                        setDataSource(filePath)
                    }
                    prepare()
                    setVolume(1.0f, 1.0f) // Maksimum volume
                    setOnCompletionListener {
                        Log.d(TAG, "Mesaj bitti")
                        isMessagePlaying = false
                        onMessageFinished()
                    }
                    setOnErrorListener { _, w, e ->
                        Log.e(TAG, "MediaPlayer hata: $w/$e")
                        isMessagePlaying = false
                        onMessageFinished()
                        true
                    }
                    start()
                    isMessagePlaying = true
                    Log.d(TAG, ">>> SES MESAJI CALINIYOR (STREAM_MUSIC -> HOPARLOR -> MIKROFON -> ARAYAN)")
                }

            } else {
                Log.w(TAG, "Ses dosyasi yok!")
                onMessageFinished()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ses calma hatasi", e)
            onMessageFinished()
        }
    }

    private fun stopCurrentPlayback() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
        isMessagePlaying = false
    }

    /**
     * Mesaj bittikten sonra tus bekleme
     */
    private fun onMessageFinished() {
        Log.d(TAG, "Tus bekleniyor (1=tekrar, 2=baglan) - 30sn timeout")

        handler.postDelayed({
            if (!isConnectedToPharmacist) {
                Log.d(TAG, "Timeout - otomatik baglaniyor")
                connectToPharmacist()
            }
        }, AUTO_CONNECT_TIMEOUT_MS)
    }

    // ========================================
    // DTMF TUS ALGILAMA
    // ========================================

    private fun startDtmfDetection() {
        dtmfDetector?.stopListening()
        dtmfDetector = DtmfDetector { key ->
            Log.d(TAG, "DTMF: $key")
            handler.post { handleDtmfKey(key) }
        }
        dtmfDetector?.startListening()
    }

    private fun handleDtmfKey(key: Char) {
        when (key) {
            '1' -> {
                Log.d(TAG, ">>> 1 - TEKRAR")
                handler.removeCallbacksAndMessages(null)
                playMessage()
            }
            '2' -> {
                Log.d(TAG, ">>> 2 - BAGLAN")
                handler.removeCallbacksAndMessages(null)
                connectToPharmacist()
            }
        }
    }

    // ========================================
    // ECZANEYE BAGLAMA
    // ========================================

    private fun connectToPharmacist() {
        if (isConnectedToPharmacist) return
        isConnectedToPharmacist = true

        // Mesaji durdur
        stopCurrentPlayback()

        // DTMF durdur
        dtmfDetector?.stopListening()
        dtmfDetector = null

        // Hoparloru kapat - normal kulaklik moduna gec
        try {
            audioManager.isSpeakerphoneOn = false
        } catch (_: Exception) {}

        // Eczaciyi uyar
        vibratePhone()
        sendAlertNotification()
        Log.d(TAG, "Eczaneye baglandi")
    }

    // ========================================
    // WHATSAPP
    // ========================================

    private fun sendWhatsAppLocationAuto() {
        if (whatsappSent || !prefs.whatsappEnabled || !prefs.hasLocation) return
        if (currentCallerNumber.isEmpty() || currentCallerNumber == "Bilinmeyen") return
        whatsappSent = true
        sendWhatsAppNotification(currentCallerNumber)
    }

    // ========================================
    // BILDIRIMLER
    // ========================================

    private fun vibratePhone() {
        try {
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 800), -1))
        } catch (_: Exception) {}
    }

    private fun sendAlertNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ALERT_NOTIFICATION_ID, NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ALERT)
            .setContentTitle("Arayan Kisi Hatta!")
            .setContentText("Mesaj dinletildi. Telefonu devralin.")
            .setSmallIcon(R.drawable.ic_phone)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build())
    }

    private fun sendWhatsAppNotification(phoneNumber: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val masked = if (phoneNumber.length > 4) "***${phoneNumber.takeLast(4)}" else phoneNumber

        nm.notify(WHATSAPP_NOTIFICATION_ID, NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_WHATSAPP)
            .setContentTitle("Konum Gonder - $masked")
            .setContentText("Arayan kisiye WhatsApp ile konum gonderin")
            .setSmallIcon(R.drawable.ic_phone)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(PendingIntent.getActivity(this, 101,
                WhatsAppHelper.createWhatsAppIntent(phoneNumber, WhatsAppHelper.buildMessage(prefs)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(R.drawable.ic_phone, "WhatsApp Gonder",
                PendingIntent.getService(this, 100,
                    Intent(this, AutoAnswerService::class.java).apply {
                        action = ACTION_SEND_WHATSAPP
                        putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build())
    }

    private fun createForegroundNotification(phoneNumber: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Eczane Telesekreter Aktif")
            .setContentText("Arama: $phoneNumber")
            .setSmallIcon(R.drawable.ic_phone)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(
            NOTIFICATION_CHANNEL_ID, "Eczane Telesekreter", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(
            NOTIFICATION_CHANNEL_ALERT, "Arama Uyarilari", NotificationManager.IMPORTANCE_HIGH
        ).apply { enableVibration(true) })
        nm.createNotificationChannel(NotificationChannel(
            NOTIFICATION_CHANNEL_WHATSAPP, "WhatsApp Konum", NotificationManager.IMPORTANCE_HIGH
        ).apply { enableVibration(true) })
    }

    // ========================================
    // TEMIZLIK
    // ========================================

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        dtmfDetector?.stopListening()
        dtmfDetector = null
        stopCurrentPlayback()
        try {
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
        isConnectedToPharmacist = false
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
