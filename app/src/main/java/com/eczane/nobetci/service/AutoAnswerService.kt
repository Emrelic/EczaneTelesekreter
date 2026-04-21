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
 * Nöbetçi Eczane IVR (Interactive Voice Response) Servisi.
 *
 * Akış:
 * 1. Gelen arama algılanır ve otomatik cevaplanır
 * 2. Hoparlör açılır
 * 3. Ses mesajı çalınır (eczane bilgileri + konum + yönlendirme)
 * 4. Mesaj sırasında arayan kişiye WhatsApp ile konum gönderilir
 * 5. DTMF tuş algılama başlar:
 *    - 1'e basarsa: Mesaj tekrar çalınır
 *    - 2'ye basarsa: Hoparlör kapanır, telefon çalar, eczacı devralır
 * 6. Hiçbir tuşa basılmazsa belirli süre sonra otomatik eczaneye bağlar
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
        // Tuşa basılmazsa otomatik bağlanma süresi (ms)
        private const val AUTO_CONNECT_TIMEOUT_MS = 30000L // 30 saniye
    }

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager
    private lateinit var prefs: PrefsManager
    private var isPlaying = false
    private var currentCallerNumber: String = ""
    private var dtmfDetector: DtmfDetector? = null
    private var whatsappSent = false
    private var isConnectedToPharmacist = false

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
                Log.d(TAG, "Gelen arama isleniyor: $phoneNumber")

                startForeground(NOTIFICATION_ID, createForegroundNotification(phoneNumber))

                handler.postDelayed({
                    answerCall()
                }, ANSWER_DELAY_MS)
            }

            ACTION_CALL_ENDED -> {
                Log.d(TAG, "Arama sona erdi, temizleniyor")
                cleanup()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_SEND_WHATSAPP -> {
                val phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: ""
                if (phoneNumber.isNotEmpty()) {
                    WhatsAppHelper.sendLocationMessage(this, phoneNumber, prefs)
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(WHATSAPP_NOTIFICATION_ID)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ========================
    // ANA AKIŞ
    // ========================

    /**
     * 1. Aramayı otomatik cevapla
     */
    private fun answerCall() {
        try {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecomManager.acceptRingingCall()
            Log.d(TAG, "Arama cevaplaniyor...")

            handler.postDelayed({
                enableSpeakerAndPlay()
            }, 1000L)

        } catch (e: SecurityException) {
            Log.e(TAG, "Arama cevaplanamadi - izin hatasi", e)
        } catch (e: Exception) {
            Log.e(TAG, "Arama cevaplanamadi", e)
        }
    }

    /**
     * 2. Hoparlörü aç ve ses mesajını çal
     */
    private fun enableSpeakerAndPlay() {
        try {
            audioManager.mode = AudioManager.MODE_IN_CALL
            audioManager.isSpeakerphoneOn = true

            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            audioManager.setStreamVolume(
                AudioManager.STREAM_VOICE_CALL,
                (maxVolume * 0.8).toInt(),
                0
            )

            Log.d(TAG, "Hoparlor acildi, ses mesaji caliniyor...")
            playMessage()

            // WhatsApp konum gönder (mesaj çalarken)
            sendWhatsAppLocationAuto()

        } catch (e: Exception) {
            Log.e(TAG, "Hoparlor/ses hatasi", e)
        }
    }

    /**
     * 3. Ses mesajını çal
     */
    private fun playMessage() {
        try {
            // Önceki player'ı temizle
            mediaPlayer?.apply {
                try { if (isPlaying()) stop() } catch (_: Exception) {}
                release()
            }
            mediaPlayer = null

            // DTMF algılamayı başlat (mesaj çalarken de tuşa basabilsin)
            startDtmfDetection()

            mediaPlayer = if (prefs.usesCustomAudio && prefs.audioFilePath != null) {
                val filePath = prefs.audioFilePath!!
                Log.d(TAG, "Ozel ses dosyasi caliniyor: $filePath")
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    if (filePath.startsWith("content://")) {
                        setDataSource(this@AutoAnswerService, Uri.parse(filePath))
                    } else {
                        setDataSource(filePath)
                    }
                    prepare()
                }
            } else {
                Log.d(TAG, "Varsayilan ses mesaji caliniyor")
                MediaPlayer.create(this, R.raw.varsayilan_mesaj)?.apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                }
            }

            mediaPlayer?.apply {
                setOnCompletionListener {
                    Log.d(TAG, "Ses mesaji tamamlandi, tus bekleniyor...")
                    onMessageFinished()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer hatasi: what=$what, extra=$extra")
                    onMessageFinished()
                    true
                }
                start()
                this@AutoAnswerService.isPlaying = true
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ses calma hatasi", e)
            onMessageFinished()
        }
    }

    /**
     * 4. Mesaj bitti - tuş bekleme moduna geç
     */
    private fun onMessageFinished() {
        isPlaying = false
        Log.d(TAG, "Mesaj bitti - DTMF tus bekleniyor (30 sn timeout)")

        // Otomatik bağlanma zamanlayıcı başlat
        // Hiçbir tuşa basılmazsa belirli süre sonra eczaneye bağla
        handler.postDelayed({
            if (!isConnectedToPharmacist) {
                Log.d(TAG, "Timeout - otomatik eczaneye baglaniyor")
                connectToPharmacist()
            }
        }, AUTO_CONNECT_TIMEOUT_MS)
    }

    // ========================
    // DTMF TUŞ ALGILAMA
    // ========================

    /**
     * DTMF dinlemeyi başlat
     */
    private fun startDtmfDetection() {
        dtmfDetector?.stopListening()

        dtmfDetector = DtmfDetector { key ->
            Log.d(TAG, "DTMF tus algilandi: $key")
            handler.post {
                handleDtmfKey(key)
            }
        }
        dtmfDetector?.startListening()
    }

    /**
     * Algılanan DTMF tuşunu işle
     */
    private fun handleDtmfKey(key: Char) {
        when (key) {
            '1' -> {
                // 1'e basıldı → Mesajı tekrar çal
                Log.d(TAG, "1'e basildi - mesaj tekrar caliniyor")
                handler.removeCallbacksAndMessages(null) // Timeout'u iptal et
                playMessage()
            }
            '2' -> {
                // 2'ye basıldı → Eczaneye bağla
                Log.d(TAG, "2'ye basildi - eczaneye baglaniyor")
                handler.removeCallbacksAndMessages(null) // Timeout'u iptal et
                connectToPharmacist()
            }
        }
    }

    // ========================
    // ECZANEYE BAĞLAMA
    // ========================

    /**
     * Arayan kişiyi eczaneye bağla.
     * Hoparlörü kapatır, DTMF'i durdurur, eczacıyı uyarır.
     */
    private fun connectToPharmacist() {
        if (isConnectedToPharmacist) return
        isConnectedToPharmacist = true

        Log.d(TAG, "Eczaneye baglama basladi")

        // Çalan mesajı durdur
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer durdurma hatasi", e)
        }
        isPlaying = false

        // DTMF algılamayı durdur
        dtmfDetector?.stopListening()
        dtmfDetector = null

        // Hoparlörü kapat - normal görüşme moduna geç
        try {
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_IN_CALL
        } catch (e: Exception) {
            Log.e(TAG, "Hoparlor kapatma hatasi", e)
        }

        // Eczacıyı uyar - titreşim
        vibratePhone()

        // Bildirim gönder
        sendAlertNotification()

        Log.d(TAG, "Eczaneye baglandi - eczaci telefonu devralabilir")
    }

    // ========================
    // WHATSAPP KONUM GÖNDERME
    // ========================

    /**
     * Mesaj çalarken otomatik olarak WhatsApp konum bildirimi gönder
     */
    private fun sendWhatsAppLocationAuto() {
        if (whatsappSent) return
        if (!prefs.whatsappEnabled) return
        if (!prefs.hasLocation) return
        if (currentCallerNumber.isEmpty() || currentCallerNumber == "Bilinmeyen") return

        whatsappSent = true
        // Bildirim gönder (eczacı tıklayınca WhatsApp açılır)
        sendWhatsAppNotification(currentCallerNumber)
    }

    // ========================
    // BİLDİRİMLER
    // ========================

    private fun vibratePhone() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val pattern = longArrayOf(0, 500, 200, 500, 200, 800, 200, 500)
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (e: Exception) {
            Log.e(TAG, "Titresim hatasi", e)
        }
    }

    private fun sendAlertNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ALERT)
            .setContentTitle("Arayan Kisi Hatta!")
            .setContentText("Mesaj dinletildi. Arayan kisi eczaneye baglanmak istiyor.")
            .setSmallIcon(R.drawable.ic_phone)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        nm.notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun sendWhatsAppNotification(phoneNumber: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val whatsappIntent = Intent(this, AutoAnswerService::class.java).apply {
            action = ACTION_SEND_WHATSAPP
            putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
        }
        val whatsappPendingIntent = PendingIntent.getService(
            this, 100, whatsappIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val directMessage = WhatsAppHelper.buildMessage(prefs)
        val directIntent = WhatsAppHelper.createWhatsAppIntent(phoneNumber, directMessage)
        val directPendingIntent = PendingIntent.getActivity(
            this, 101, directIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val maskedNumber = if (phoneNumber.length > 4) "***${phoneNumber.takeLast(4)}" else phoneNumber

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_WHATSAPP)
            .setContentTitle("Konum Gonder - $maskedNumber")
            .setContentText("Tiklayin: Arayan kisiye eczane konumunu WhatsApp ile gonderin")
            .setSmallIcon(R.drawable.ic_phone)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(directPendingIntent)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_phone, "WhatsApp ile Gonder", whatsappPendingIntent)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Arayan: $maskedNumber\n" +
                    "${prefs.eczaneAdi} konumunu WhatsApp ile gondermek icin tiklayin."
                )
            )
            .build()
        nm.notify(WHATSAPP_NOTIFICATION_ID, notification)
        Log.d(TAG, "WhatsApp konum bildirimi gonderildi: $maskedNumber")
    }

    private fun createForegroundNotification(phoneNumber: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Nobetci Eczane IVR - Aktif")
            .setContentText("Gelen arama isleniyor: $phoneNumber")
            .setSmallIcon(R.drawable.ic_phone)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        nm.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "Nobetci Eczane Servisi",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Arka planda calisan nobetci eczane servisi" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ALERT, "Arama Uyarilari",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Arayan kisi baglanmak istediginde gelen uyarilar"
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_WHATSAPP, "WhatsApp Konum",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "WhatsApp ile konum gonderme bildirimleri"
                enableVibration(true)
            }
        )
    }

    // ========================
    // TEMİZLİK
    // ========================

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)

        dtmfDetector?.stopListening()
        dtmfDetector = null

        try {
            mediaPlayer?.apply {
                try { if (isPlaying()) stop() } catch (_: Exception) {}
                release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer temizleme hatasi", e)
        }

        try {
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            Log.e(TAG, "Ses ayarlari sifirlama hatasi", e)
        }

        isPlaying = false
        isConnectedToPharmacist = false
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
