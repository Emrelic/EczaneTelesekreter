package com.eczane.nobetci.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/**
 * WhatsApp uzerinden konum ve mesaj gonderme yardimci sinifi.
 */
object WhatsAppHelper {

    private const val TAG = "WhatsAppHelper"
    private const val WHATSAPP_PACKAGE = "com.whatsapp"
    private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"

    fun isWhatsAppInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(WHATSAPP_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            try {
                context.packageManager.getPackageInfo(WHATSAPP_BUSINESS_PACKAGE, 0)
                true
            } catch (e2: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    private fun getWhatsAppPackage(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(WHATSAPP_PACKAGE, 0)
            WHATSAPP_PACKAGE
        } catch (_: PackageManager.NameNotFoundException) {
            WHATSAPP_BUSINESS_PACKAGE
        }
    }

    fun createMapsLink(latitude: Double, longitude: Double, label: String): String {
        val encodedLabel = Uri.encode(label)
        return "https://maps.google.com/maps?q=$latitude,$longitude($encodedLabel)"
    }

    fun buildMessage(prefs: PrefsManager): String {
        val sb = StringBuilder()

        sb.append("*${prefs.eczaneAdi} - Nobetci Eczane*\n\n")

        if (prefs.eczaneAdres.isNotEmpty()) {
            sb.append("Adres: ${prefs.eczaneAdres}\n\n")
        }

        if (prefs.eczaneTelefon.isNotEmpty()) {
            sb.append("Tel: ${prefs.eczaneTelefon}\n\n")
        }

        if (prefs.hasLocation) {
            val mapsLink = createMapsLink(
                prefs.eczaneLatitude,
                prefs.eczaneLongitude,
                prefs.eczaneAdi
            )
            sb.append("Konum: $mapsLink\n\n")
        }

        if (prefs.whatsappMessage.isNotEmpty()) {
            sb.append("${prefs.whatsappMessage}\n\n")
        }

        sb.append("Gecmis olsun, saglikli gunler dileriz.")

        return sb.toString()
    }

    fun formatPhoneNumber(phoneNumber: String): String {
        var cleaned = phoneNumber.replace(Regex("[^0-9+]"), "")

        if (cleaned.startsWith("+")) {
            return cleaned.removePrefix("+")
        }

        if (cleaned.startsWith("0")) {
            cleaned = "90${cleaned.removePrefix("0")}"
        }

        if (!cleaned.startsWith("90") && cleaned.length == 10) {
            cleaned = "90$cleaned"
        }

        return cleaned
    }

    /**
     * WhatsApp ile mesaj gonderme Intent'i olustur.
     * vnd.android.cursor.item/vnd.com.whatsapp.profile ile
     * dogrudan kisinin sohbetini acar ve mesaji yazar.
     */
    fun createWhatsAppIntent(phoneNumber: String, message: String): Intent {
        val formattedNumber = formatPhoneNumber(phoneNumber)
        val encodedMessage = Uri.encode(message)

        // wa.me linki ile WhatsApp'i ac - mesaj hazir gelir
        val uri = Uri.parse("https://wa.me/$formattedNumber?text=$encodedMessage")

        return Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /**
     * WhatsApp API ile mesaji dogrudan gonder.
     * Bu yontem mesaji WhatsApp'ta acar, kullanici sadece gonder tusuna basar.
     *
     * NOT: WhatsApp guvenlik kisitlamasi nedeniyle tam otomatik gonderim mumkun degildir.
     * Kullanicinin 1 kez "Gonder" tusuna basmasi gerekir.
     */
    fun sendLocationMessage(context: Context, phoneNumber: String, prefs: PrefsManager): Boolean {
        return try {
            if (!isWhatsAppInstalled(context)) {
                Log.e(TAG, "WhatsApp yuklu degil")
                return false
            }

            val message = buildMessage(prefs)
            val formattedNumber = formatPhoneNumber(phoneNumber)
            val pkg = getWhatsAppPackage(context)

            // Yontem: ACTION_SEND ile dogrudan WhatsApp'a gonder
            // Bu yontem mesaji "Gonder" ekraninda hazir acar
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage(pkg)
                putExtra(Intent.EXTRA_TEXT, message)
                // Hedef numarayi belirle
                putExtra("jid", "$formattedNumber@s.whatsapp.net")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            context.startActivity(sendIntent)
            Log.d(TAG, "WhatsApp mesaji acildi: $phoneNumber (ACTION_SEND + jid)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "WhatsApp ACTION_SEND hatasi, wa.me fallback deneniyor", e)
            // Fallback: wa.me linki
            try {
                val message = buildMessage(prefs)
                val intent = createWhatsAppIntent(phoneNumber, message)
                context.startActivity(intent)
                true
            } catch (e2: Exception) {
                Log.e(TAG, "WhatsApp tamamen basarisiz", e2)
                false
            }
        }
    }
}
