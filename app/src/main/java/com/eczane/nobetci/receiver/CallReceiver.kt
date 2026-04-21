package com.eczane.nobetci.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.eczane.nobetci.service.AutoAnswerService
import com.eczane.nobetci.util.PrefsManager

/**
 * Gelen aramaları algılayan BroadcastReceiver.
 * Telefon çaldığında AutoAnswerService'i başlatır.
 */
class CallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val prefs = PrefsManager(context)
        if (!prefs.isServiceActive) {
            Log.d(TAG, "Servis aktif değil, arama yoksayılıyor")
            return
        }

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        Log.d(TAG, "Telefon durumu: $state")

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                Log.d(TAG, "Gelen arama: $incomingNumber")

                // AutoAnswerService'i başlat
                val serviceIntent = Intent(context, AutoAnswerService::class.java).apply {
                    action = AutoAnswerService.ACTION_INCOMING_CALL
                    putExtra(AutoAnswerService.EXTRA_PHONE_NUMBER, incomingNumber)
                }
                context.startForegroundService(serviceIntent)
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                Log.d(TAG, "Arama cevaplanmış durumda")
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                Log.d(TAG, "Arama sona erdi")
                // Servisi durdur
                val serviceIntent = Intent(context, AutoAnswerService::class.java).apply {
                    action = AutoAnswerService.ACTION_CALL_ENDED
                }
                try {
                    context.startService(serviceIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Servis durdurma hatası", e)
                }
            }
        }
    }
}
