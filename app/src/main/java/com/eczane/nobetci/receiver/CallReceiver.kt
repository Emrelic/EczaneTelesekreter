package com.eczane.nobetci.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.eczane.nobetci.service.AutoAnswerService
import com.eczane.nobetci.util.PrefsManager

/**
 * Gelen aramalari algilayan BroadcastReceiver.
 * Telefon caldiginda AutoAnswerService'i baslatir.
 */
class CallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val prefs = PrefsManager(context)
        if (!prefs.isServiceActive) {
            Log.d(TAG, "Servis aktif degil, arama yoksayiliyor")
            return
        }

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        Log.d(TAG, "Telefon durumu: $state")

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                Log.d(TAG, "Gelen arama: $incomingNumber")

                val serviceIntent = Intent(context, AutoAnswerService::class.java).apply {
                    action = AutoAnswerService.ACTION_INCOMING_CALL
                    putExtra(AutoAnswerService.EXTRA_PHONE_NUMBER, incomingNumber)
                }
                try {
                    context.startForegroundService(serviceIntent)
                    Log.d(TAG, "AutoAnswerService BASLATILDI")
                } catch (e: Exception) {
                    Log.e(TAG, "Foreground service baslatilamadi: ${e.message}", e)
                    // Android 12+ arka plan kisitlamasi - fallback dene
                    try {
                        context.startService(serviceIntent)
                        Log.d(TAG, "Normal service olarak baslatildi (fallback)")
                    } catch (e2: Exception) {
                        Log.e(TAG, "Service hic baslatilamadi: ${e2.message}", e2)
                    }
                }
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                Log.d(TAG, "Arama sona erdi")
                val serviceIntent = Intent(context, AutoAnswerService::class.java).apply {
                    action = AutoAnswerService.ACTION_CALL_ENDED
                }
                try {
                    context.startService(serviceIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Servis durdurma hatasi: ${e.message}")
                }
            }
        }
    }
}
