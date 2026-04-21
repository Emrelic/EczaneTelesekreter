package com.eczane.nobetci.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Uygulama ayarlarını yöneten sınıf
 */
class PrefsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("nobetci_eczane_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SERVICE_ACTIVE = "service_active"
        private const val KEY_AUDIO_FILE_PATH = "audio_file_path"
        private const val KEY_ECZANE_ADI = "eczane_adi"
        private const val KEY_ECZANE_ADRES = "eczane_adres"
        private const val KEY_ECZANE_TELEFON = "eczane_telefon"
        private const val KEY_MESAJ_SURESI_MS = "mesaj_suresi_ms"
        private const val KEY_BEKLEME_SURESI_MS = "bekleme_suresi_ms"
        private const val KEY_USES_CUSTOM_AUDIO = "uses_custom_audio"
        private const val KEY_ECZANE_LATITUDE = "eczane_latitude"
        private const val KEY_ECZANE_LONGITUDE = "eczane_longitude"
        private const val KEY_WHATSAPP_ENABLED = "whatsapp_enabled"
        private const val KEY_WHATSAPP_MESSAGE = "whatsapp_message"
    }

    /** Servis aktif mi? */
    var isServiceActive: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_ACTIVE, value).apply()

    /** Özel ses dosyası yolu */
    var audioFilePath: String?
        get() = prefs.getString(KEY_AUDIO_FILE_PATH, null)
        set(value) = prefs.edit().putString(KEY_AUDIO_FILE_PATH, value).apply()

    /** Özel ses dosyası mı kullanılıyor? */
    var usesCustomAudio: Boolean
        get() = prefs.getBoolean(KEY_USES_CUSTOM_AUDIO, false)
        set(value) = prefs.edit().putBoolean(KEY_USES_CUSTOM_AUDIO, value).apply()

    /** Eczane adı */
    var eczaneAdi: String
        get() = prefs.getString(KEY_ECZANE_ADI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ECZANE_ADI, value).apply()

    /** Eczane adresi */
    var eczaneAdres: String
        get() = prefs.getString(KEY_ECZANE_ADRES, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ECZANE_ADRES, value).apply()

    /** Eczane telefonu */
    var eczaneTelefon: String
        get() = prefs.getString(KEY_ECZANE_TELEFON, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ECZANE_TELEFON, value).apply()

    /** Ses mesajı süresi (ms) - varsayılan 15 saniye */
    var mesajSuresiMs: Long
        get() = prefs.getLong(KEY_MESAJ_SURESI_MS, 15000L)
        set(value) = prefs.edit().putLong(KEY_MESAJ_SURESI_MS, value).apply()

    /** Bekleme süresi (ms) - mesajdan sonra telefon çalmadan önce bekleme */
    var beklemeSuresiMs: Long
        get() = prefs.getLong(KEY_BEKLEME_SURESI_MS, 3000L)
        set(value) = prefs.edit().putLong(KEY_BEKLEME_SURESI_MS, value).apply()

    /** Eczane enlem (latitude) */
    var eczaneLatitude: Double
        get() = prefs.getString(KEY_ECZANE_LATITUDE, "0.0")?.toDoubleOrNull() ?: 0.0
        set(value) = prefs.edit().putString(KEY_ECZANE_LATITUDE, value.toString()).apply()

    /** Eczane boylam (longitude) */
    var eczaneLongitude: Double
        get() = prefs.getString(KEY_ECZANE_LONGITUDE, "0.0")?.toDoubleOrNull() ?: 0.0
        set(value) = prefs.edit().putString(KEY_ECZANE_LONGITUDE, value.toString()).apply()

    /** Konum kaydedilmiş mi? */
    val hasLocation: Boolean
        get() = eczaneLatitude != 0.0 && eczaneLongitude != 0.0

    /** WhatsApp konum gönderme aktif mi? */
    var whatsappEnabled: Boolean
        get() = prefs.getBoolean(KEY_WHATSAPP_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_WHATSAPP_ENABLED, value).apply()

    /** WhatsApp ile gönderilecek ek mesaj */
    var whatsappMessage: String
        get() = prefs.getString(KEY_WHATSAPP_MESSAGE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WHATSAPP_MESSAGE, value).apply()
}
