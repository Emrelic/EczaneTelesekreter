package com.eczane.nobetci.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/**
 * WhatsApp üzerinden konum ve mesaj gönderme yardımcı sınıfı.
 *
 * Arayan kişinin numarasına WhatsApp mesajı olarak:
 * - Eczane adı
 * - Adres
 * - Google Maps konum linki
 * gönderir.
 */
object WhatsAppHelper {

    private const val TAG = "WhatsAppHelper"
    private const val WHATSAPP_PACKAGE = "com.whatsapp"
    private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"

    /**
     * WhatsApp yüklü mü kontrol et
     */
    fun isWhatsAppInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(WHATSAPP_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            // WhatsApp Business'ı dene
            try {
                context.packageManager.getPackageInfo(WHATSAPP_BUSINESS_PACKAGE, 0)
                true
            } catch (e2: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    /**
     * Google Maps konum linki oluştur
     */
    fun createMapsLink(latitude: Double, longitude: Double, label: String): String {
        val encodedLabel = Uri.encode(label)
        return "https://maps.google.com/maps?q=$latitude,$longitude($encodedLabel)"
    }

    /**
     * WhatsApp mesaj metni oluştur
     */
    fun buildMessage(prefs: PrefsManager): String {
        val sb = StringBuilder()

        sb.append("*${prefs.eczaneAdi} - Nöbetçi Eczane*\n\n")

        if (prefs.eczaneAdres.isNotEmpty()) {
            sb.append("Adres: ${prefs.eczaneAdres}\n\n")
        }

        if (prefs.eczaneTelefon.isNotEmpty()) {
            sb.append("Tel: ${prefs.eczaneTelefon}\n\n")
        }

        // Konum linki
        if (prefs.hasLocation) {
            val mapsLink = createMapsLink(
                prefs.eczaneLatitude,
                prefs.eczaneLongitude,
                prefs.eczaneAdi
            )
            sb.append("Konum: $mapsLink\n\n")
        }

        // Ek mesaj
        if (prefs.whatsappMessage.isNotEmpty()) {
            sb.append("${prefs.whatsappMessage}\n\n")
        }

        sb.append("Geçmiş olsun, sağlıklı günler dileriz.")

        return sb.toString()
    }

    /**
     * Telefon numarasını uluslararası formata çevir.
     * Örn: 05551234567 -> 905551234567
     */
    fun formatPhoneNumber(phoneNumber: String): String {
        var cleaned = phoneNumber.replace(Regex("[^0-9+]"), "")

        // Zaten + ile başlıyorsa sadece + işaretini kaldır
        if (cleaned.startsWith("+")) {
            return cleaned.removePrefix("+")
        }

        // 0 ile başlıyorsa Türkiye kodu ekle
        if (cleaned.startsWith("0")) {
            cleaned = "90${cleaned.removePrefix("0")}"
        }

        // Hiçbir ülke kodu yoksa Türkiye varsay
        if (!cleaned.startsWith("90") && cleaned.length == 10) {
            cleaned = "90$cleaned"
        }

        return cleaned
    }

    /**
     * WhatsApp ile mesaj gönderme Intent'i oluştur.
     * Bu Intent, WhatsApp'ı doğrudan arayan kişinin sohbetinde açar.
     */
    fun createWhatsAppIntent(phoneNumber: String, message: String): Intent {
        val formattedNumber = formatPhoneNumber(phoneNumber)
        val encodedMessage = Uri.encode(message)

        // wa.me linki ile WhatsApp'ı aç
        val uri = Uri.parse("https://wa.me/$formattedNumber?text=$encodedMessage")

        return Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /**
     * Belirli bir numaraya WhatsApp mesajı gönder.
     * WhatsApp açılır, mesaj hazır olarak gelir, kullanıcı sadece gönder'e basar.
     */
    fun sendLocationMessage(context: Context, phoneNumber: String, prefs: PrefsManager): Boolean {
        return try {
            if (!isWhatsAppInstalled(context)) {
                Log.e(TAG, "WhatsApp yüklü değil")
                return false
            }

            val message = buildMessage(prefs)
            val intent = createWhatsAppIntent(phoneNumber, message)
            context.startActivity(intent)

            Log.d(TAG, "WhatsApp mesajı açıldı: $phoneNumber")
            true
        } catch (e: Exception) {
            Log.e(TAG, "WhatsApp mesaj gönderme hatası", e)
            false
        }
    }
}
