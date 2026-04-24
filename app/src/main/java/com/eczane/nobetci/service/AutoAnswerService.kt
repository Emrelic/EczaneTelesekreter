package com.eczane.nobetci.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
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
import com.eczane.nobetci.util.CallAudioPlayer
import com.eczane.nobetci.util.DtmfDetector
import com.eczane.nobetci.util.PrefsManager
import com.eczane.nobetci.util.WhatsAppHelper

/**
 * Eczane Telesekreter IVR Servisi.
 *
 * MODE_IN_COMMUNICATION + AudioTrack(USAGE_VOICE_COMMUNICATION)
 * ile arayan kisiye ses dosyasi dinletir.
 * Hoparlor ve mikrofon kullanmadan dogrudan sebeke uplink'ine yazar.
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

        private const val ANSWER_DELAY_MS = 500L
        private const val SETUP_DELAY_MS = 1000L  // Arama tam baglansin
        private const val AUTO_CONNECT_TIMEOUT_MS = 30000L
    }

    // Dogrudan sebeke kanalina ses yazma
    private var callAudioPlayer: CallAudioPlayer? = null

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager
    private lateinit var prefs: PrefsManager
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
                val phone = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: "Bilinmeyen"
                currentCallerNumber = phone
                whatsappSent = false
                isConnectedToPharmacist = false
                Log.d(TAG, "=== GELEN ARAMA: $phone ===")

                startForeground(NOTIFICATION_ID, createForegroundNotification(phone))
                handler.postDelayed({ answerCall() }, ANSWER_DELAY_MS)
            }

            ACTION_CALL_ENDED -> {
                Log.d(TAG, "=== ARAMA BITTI ===")
                cleanup()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_SEND_WHATSAPP -> {
                val phone = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: ""
                if (phone.isNotEmpty()) {
                    WhatsAppHelper.sendLocationMessage(this, phone, prefs)
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

    private fun answerCall() {
        try {
            val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tm.acceptRingingCall()
            Log.d(TAG, "Arama CEVAPLANDI - ses kurulumu bekleniyor...")

            // Aramanin tam baglanmasini bekle
            handler.postDelayed({ setupAndPlay() }, SETUP_DELAY_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Arama cevaplanamadi", e)
        }
    }

    private fun setupAndPlay() {
        try {
            Log.d(TAG, "AudioMode=${audioManager.mode}")
            playMessage()
            sendWhatsAppLocationAuto()
        } catch (e: Exception) {
            Log.e(TAG, "Ses kurulum hatasi", e)
        }
    }

    private fun playMessage() {
        stopCurrentPlayback()
        startDtmfDetection()

        val filePath = getAudioFilePath() ?: return

        Log.d(TAG, "Ses dosyasi: $filePath")

        callAudioPlayer = CallAudioPlayer(this)
        callAudioPlayer?.play(
            filePath = filePath,
            onComplete = {
                handler.post {
                    Log.d(TAG, "Ses oynatma tamamlandi")
                    onMessageFinished()
                }
            },
            onError = { err ->
                Log.e(TAG, "Ses hatasi: $err")
            }
        )
    }

    private fun getAudioFilePath(): String? {
        if (prefs.usesCustomAudio && prefs.audioFilePath != null) {
            return prefs.audioFilePath
        }
        // Raw resource'u dosyaya kopyala
        return try {
            val file = java.io.File(filesDir, "varsayilan_mesaj.wav")
            if (!file.exists()) {
                resources.openRawResource(R.raw.varsayilan_mesaj).use { inp ->
                    file.outputStream().use { out -> inp.copyTo(out) }
                }
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Dosya hatasi", e)
            null
        }
    }

    private fun stopCurrentPlayback() {
        callAudioPlayer?.stop()
        callAudioPlayer = null
    }

    private fun onMessageFinished() {
        Log.d(TAG, "Mesaj bitti - tus bekleniyor (1=tekrar, 2=baglan)")
        handler.postDelayed({
            if (!isConnectedToPharmacist) {
                Log.d(TAG, "30sn timeout - otomatik baglaniyor")
                connectToPharmacist()
            }
        }, AUTO_CONNECT_TIMEOUT_MS)
    }

    // ========================================
    // DTMF
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
                Log.d(TAG, ">>> 1 BASILDI - TEKRAR")
                handler.removeCallbacksAndMessages(null)
                playMessage()
            }
            '2' -> {
                Log.d(TAG, ">>> 2 BASILDI - BAGLAN")
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

        stopCurrentPlayback()
        dtmfDetector?.stopListening()
        dtmfDetector = null

        // Ses ayarlari CallAudioPlayer.stop() tarafindan geri yuklenir

        vibratePhone()
        sendAlertNotification()
        Log.d(TAG, "=== ECZANEYE BAGLANDI ===")
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
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 800), -1))
        } catch (_: Exception) {}
    }

    private fun sendAlertNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ALERT_NOTIFICATION_ID, NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ALERT)
            .setContentTitle("Arayan Kisi Hatta!")
            .setContentText("Telefonu devralin.")
            .setSmallIcon(R.drawable.ic_phone)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true).build())
    }

    private fun sendWhatsAppNotification(phoneNumber: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val masked = if (phoneNumber.length > 4) "***${phoneNumber.takeLast(4)}" else phoneNumber
        nm.notify(WHATSAPP_NOTIFICATION_ID, NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_WHATSAPP)
            .setContentTitle("Konum Gonder - $masked")
            .setContentText("WhatsApp ile konum gonderin")
            .setSmallIcon(R.drawable.ic_phone)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(PendingIntent.getActivity(this, 101,
                WhatsAppHelper.createWhatsAppIntent(phoneNumber, WhatsAppHelper.buildMessage(prefs)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(R.drawable.ic_phone, "Gonder", PendingIntent.getService(this, 100,
                Intent(this, AutoAnswerService::class.java).apply {
                    action = ACTION_SEND_WHATSAPP; putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
                }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true).build())
    }

    private fun createForegroundNotification(phone: String): Notification =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Eczane Telesekreter")
            .setContentText("Arama: $phone")
            .setSmallIcon(R.drawable.ic_phone).setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()

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
        dtmfDetector?.stopListening(); dtmfDetector = null
        stopCurrentPlayback()
        try { audioManager.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}
        isConnectedToPharmacist = false
    }

    override fun onDestroy() { cleanup(); super.onDestroy() }
}
