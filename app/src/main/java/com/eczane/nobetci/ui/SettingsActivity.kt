package com.eczane.nobetci.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.eczane.nobetci.R
import com.eczane.nobetci.util.PrefsManager
import com.eczane.nobetci.util.WhatsAppHelper
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Tüm uygulama ayarlarını tek ekrandan yönetme.
 * - Eczane bilgileri
 * - Konum ayarları
 * - Ses mesajı ayarları
 * - WhatsApp mesaj ayarları
 * - Zamanlama ayarları
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 300
    }

    private lateinit var prefs: PrefsManager

    // Eczane bilgileri
    private lateinit var etEczaneAdi: EditText
    private lateinit var etEczaneAdres: EditText
    private lateinit var etEczaneTelefon: EditText

    // Konum
    private lateinit var tvKonumStatus: TextView
    private lateinit var btnKonumGps: Button
    private lateinit var etLatitude: EditText
    private lateinit var etLongitude: EditText

    // Ses mesajı
    private lateinit var btnSesKendiSes: Button
    private lateinit var btnSesYapayZeka: Button
    private lateinit var tvSesStatus: TextView

    // WhatsApp
    private lateinit var switchWhatsapp: SwitchMaterial
    private lateinit var etWhatsappMesaj: EditText
    private lateinit var btnWhatsappOnizleme: Button

    // Zamanlama
    private lateinit var seekBarTimeout: SeekBar
    private lateinit var tvTimeout: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = PrefsManager(this)

        supportActionBar?.apply {
            title = "Ayarlar"
            setDisplayHomeAsUpEnabled(true)
        }

        initViews()
        loadAllSettings()
    }

    private fun initViews() {
        // Eczane bilgileri
        etEczaneAdi = findViewById(R.id.setEczaneAdi)
        etEczaneAdres = findViewById(R.id.setEczaneAdres)
        etEczaneTelefon = findViewById(R.id.setEczaneTelefon)

        // Konum
        tvKonumStatus = findViewById(R.id.setKonumStatus)
        btnKonumGps = findViewById(R.id.setBtnKonumGps)
        etLatitude = findViewById(R.id.setLatitude)
        etLongitude = findViewById(R.id.setLongitude)

        // Ses mesajı
        btnSesKendiSes = findViewById(R.id.setBtnKendiSes)
        btnSesYapayZeka = findViewById(R.id.setBtnYapayZeka)
        tvSesStatus = findViewById(R.id.setSesStatus)

        // WhatsApp
        switchWhatsapp = findViewById(R.id.setSwitchWhatsapp)
        etWhatsappMesaj = findViewById(R.id.setWhatsappMesaj)
        btnWhatsappOnizleme = findViewById(R.id.setBtnWhatsappOnizleme)

        // Zamanlama
        seekBarTimeout = findViewById(R.id.setSeekTimeout)
        tvTimeout = findViewById(R.id.setTvTimeout)

        // --- Buton aksiyonları ---

        // GPS konum al
        btnKonumGps.setOnClickListener { getCurrentLocation() }

        // Ses kayıt ekranları
        btnSesKendiSes.setOnClickListener {
            startActivity(Intent(this, RecordMessageActivity::class.java))
        }
        btnSesYapayZeka.setOnClickListener {
            startActivity(Intent(this, TtsMessageActivity::class.java))
        }

        // WhatsApp switch
        switchWhatsapp.setOnCheckedChangeListener { _, isChecked ->
            prefs.whatsappEnabled = isChecked
        }

        // WhatsApp önizleme
        btnWhatsappOnizleme.setOnClickListener { showWhatsAppPreview() }

        // Timeout seekbar (10-60 saniye)
        seekBarTimeout.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val seconds = 10 + (progress * 5) // 10-60 arası, 5'er artış
                tvTimeout.text = "Otomatik baglama suresi: ${seconds} saniye"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Kaydet butonu
        findViewById<Button>(R.id.setBtnKaydet).setOnClickListener {
            saveAllSettings()
        }
    }

    private fun loadAllSettings() {
        etEczaneAdi.setText(prefs.eczaneAdi)
        etEczaneAdres.setText(prefs.eczaneAdres)
        etEczaneTelefon.setText(prefs.eczaneTelefon)

        if (prefs.hasLocation) {
            etLatitude.setText(prefs.eczaneLatitude.toString())
            etLongitude.setText(prefs.eczaneLongitude.toString())
        }
        updateLocationStatus()

        updateAudioStatus()

        switchWhatsapp.isChecked = prefs.whatsappEnabled
        etWhatsappMesaj.setText(prefs.whatsappMessage)

        // Timeout (varsayılan 30 saniye → seekbar progress = 4)
        val timeoutSec = (prefs.beklemeSuresiMs / 1000).toInt().coerceIn(10, 60)
        seekBarTimeout.progress = (timeoutSec - 10) / 5
        tvTimeout.text = "Otomatik baglama suresi: ${timeoutSec} saniye"
    }

    private fun saveAllSettings() {
        prefs.eczaneAdi = etEczaneAdi.text.toString().trim()
        prefs.eczaneAdres = etEczaneAdres.text.toString().trim()
        prefs.eczaneTelefon = etEczaneTelefon.text.toString().trim()

        // Konum kaydet
        val lat = etLatitude.text.toString().trim().toDoubleOrNull()
        val lon = etLongitude.text.toString().trim().toDoubleOrNull()
        if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
            prefs.eczaneLatitude = lat
            prefs.eczaneLongitude = lon
        }

        // WhatsApp mesaj
        prefs.whatsappMessage = etWhatsappMesaj.text.toString().trim()

        // Timeout
        val timeoutSec = 10 + (seekBarTimeout.progress * 5)
        prefs.beklemeSuresiMs = timeoutSec * 1000L

        updateLocationStatus()

        Toast.makeText(this, "Tum ayarlar kaydedildi!", Toast.LENGTH_SHORT).show()
    }

    private fun updateLocationStatus() {
        if (prefs.hasLocation) {
            tvKonumStatus.text = "Konum: ${String.format("%.4f", prefs.eczaneLatitude)}, ${String.format("%.4f", prefs.eczaneLongitude)}"
            tvKonumStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            tvKonumStatus.text = "Konum: Ayarlanmamis"
            tvKonumStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }
    }

    private fun updateAudioStatus() {
        if (prefs.usesCustomAudio && prefs.audioFilePath != null) {
            tvSesStatus.text = "Ses mesaji: Hazir"
            tvSesStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            tvSesStatus.text = "Ses mesaji: Henuz kaydedilmedi"
            tvSesStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }
    }

    override fun onResume() {
        super.onResume()
        updateAudioStatus()
    }

    private fun getCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
            return
        }
        fetchLocation()
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocation() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

        if (loc != null) {
            etLatitude.setText(String.format("%.6f", loc.latitude))
            etLongitude.setText(String.format("%.6f", loc.longitude))
            prefs.eczaneLatitude = loc.latitude
            prefs.eczaneLongitude = loc.longitude
            updateLocationStatus()
            Toast.makeText(this, "Konum alindi!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Konum alinamadi. GPS acik mi?", Toast.LENGTH_LONG).show()
            lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, { location ->
                runOnUiThread {
                    etLatitude.setText(String.format("%.6f", location.latitude))
                    etLongitude.setText(String.format("%.6f", location.longitude))
                    prefs.eczaneLatitude = location.latitude
                    prefs.eczaneLongitude = location.longitude
                    updateLocationStatus()
                    Toast.makeText(this, "Konum alindi!", Toast.LENGTH_SHORT).show()
                }
            }, null)
        }
    }

    private fun showWhatsAppPreview() {
        // Önce bilgileri kaydet
        saveAllSettings()

        if (!prefs.hasLocation) {
            Toast.makeText(this, "Once konum ayarlayin", Toast.LENGTH_LONG).show()
            return
        }

        val message = WhatsAppHelper.buildMessage(prefs)

        AlertDialog.Builder(this)
            .setTitle("WhatsApp Mesaj Onizleme")
            .setMessage(message)
            .setPositiveButton("Tamam", null)
            .setNeutralButton("Kendime Test Gonder") { _, _ ->
                if (prefs.eczaneTelefon.isNotEmpty()) {
                    WhatsAppHelper.sendLocationMessage(this, prefs.eczaneTelefon, prefs)
                } else {
                    Toast.makeText(this, "Test icin telefon numarasi gerekli", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            fetchLocation()
        }
    }
}
