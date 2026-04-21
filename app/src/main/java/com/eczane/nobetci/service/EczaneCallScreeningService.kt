package com.eczane.nobetci.service

import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import com.eczane.nobetci.util.PrefsManager

/**
 * Call Screening Service - aramaları filtrelemek ve yönetmek için kullanılır.
 * Bu servis Android sistem tarafından çağrılır.
 */
class EczaneCallScreeningService : CallScreeningService() {

    companion object {
        private const val TAG = "EczaneCallScreening"
    }

    override fun onScreenCall(callDetails: Call.Details) {
        val prefs = PrefsManager(this)

        if (!prefs.isServiceActive) {
            // Servis aktif değilse aramaya müdahale etme
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }

        Log.d(TAG, "Arama taranıyor: ${callDetails.handle}")

        // Aramayı normal şekilde geçir - CallReceiver halledecek
        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSilenceCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()

        respondToCall(callDetails, response)
    }
}
