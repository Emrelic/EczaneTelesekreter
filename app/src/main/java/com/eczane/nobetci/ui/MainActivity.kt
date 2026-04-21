package com.eczane.nobetci.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.eczane.nobetci.R
import com.eczane.nobetci.util.PrefsManager
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Ana ekran - Nöbet modunu açma/kapama ve durumu gösterme.
 * Tüm detaylı ayarlar SettingsActivity'de yapılır.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 100
    }

    private lateinit var prefs: PrefsManager

    private lateinit var switchService: SwitchMaterial
    private lateinit var tvServiceStatus: TextView
    private lateinit var tvEczaneBilgi: TextView
    private lateinit var tvSesStatus: TextView
    private lateinit var tvKonumStatus: TextView
    private lateinit var tvWhatsappStatus: TextView
    private lateinit var btnAyarlar: Button

    private val requiredPermissions = mutableListOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.MODIFY_AUDIO_SETTINGS,
        Manifest.permission.VIBRATE,
        Manifest.permission.RECORD_AUDIO
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val callScreeningRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = PrefsManager(this)
        initViews()
        checkPermissions()
        requestCallScreeningRole()
    }

    override fun onResume() {
        super.onResume()
        updateAllStatus()
    }

    private fun initViews() {
        switchService = findViewById(R.id.switchService)
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        tvEczaneBilgi = findViewById(R.id.tvEczaneBilgi)
        tvSesStatus = findViewById(R.id.tvSesStatus)
        tvKonumStatus = findViewById(R.id.tvKonumStatus)
        tvWhatsappStatus = findViewById(R.id.tvWhatsappStatus)
        btnAyarlar = findViewById(R.id.btnAyarlar)

        // Servis acma/kapama
        switchService.isChecked = prefs.isServiceActive

        switchService.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Kontroller
                if (!hasAllPermissions()) {
                    switchService.isChecked = false
                    Toast.makeText(this, "Once gerekli izinleri verin", Toast.LENGTH_LONG).show()
                    checkPermissions()
                    return@setOnCheckedChangeListener
                }
                if (!prefs.usesCustomAudio) {
                    switchService.isChecked = false
                    Toast.makeText(this, "Once Ayarlar'dan ses mesaji kaydedin!", Toast.LENGTH_LONG).show()
                    return@setOnCheckedChangeListener
                }
            }

            prefs.isServiceActive = isChecked
            updateAllStatus()

            if (isChecked) {
                Toast.makeText(this, "NOBET MODU AKTIF!\nGelen aramalar otomatik cevaplanacak.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Nobet modu kapatildi.", Toast.LENGTH_SHORT).show()
            }
        }

        // Ayarlar ekranına git
        btnAyarlar.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun updateAllStatus() {
        // Servis durumu
        if (prefs.isServiceActive) {
            tvServiceStatus.text = "NOBET MODU: AKTIF\nGelen aramalar otomatik cevaplanacak"
            tvServiceStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            switchService.isChecked = true
        } else {
            tvServiceStatus.text = "NOBET MODU: KAPALI\nNormal telefon modu"
            tvServiceStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            switchService.isChecked = false
        }

        // Eczane bilgisi
        if (prefs.eczaneAdi.isNotEmpty()) {
            tvEczaneBilgi.text = "Eczane: ${prefs.eczaneAdi}\nAdres: ${prefs.eczaneAdres.ifEmpty { "-" }}\nTel: ${prefs.eczaneTelefon.ifEmpty { "-" }}"
            tvEczaneBilgi.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            tvEczaneBilgi.text = "Eczane bilgileri girilmemis - Ayarlar'dan girin"
            tvEczaneBilgi.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }

        // Ses durumu
        if (prefs.usesCustomAudio && prefs.audioFilePath != null) {
            tvSesStatus.text = "Ses mesaji: Hazir"
            tvSesStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            tvSesStatus.text = "Ses mesaji: KAYIT YAPILMAMIS"
            tvSesStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }

        // Konum durumu
        if (prefs.hasLocation) {
            tvKonumStatus.text = "Konum: Ayarlanmis"
            tvKonumStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            tvKonumStatus.text = "Konum: Ayarlanmamis"
            tvKonumStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }

        // WhatsApp durumu
        if (prefs.whatsappEnabled) {
            tvWhatsappStatus.text = "WhatsApp konum gonderme: Aktif"
            tvWhatsappStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            tvWhatsappStatus.text = "WhatsApp konum gonderme: Kapali"
            tvWhatsappStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        }
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSIONS_REQUEST_CODE)
        }
    }

    private fun requestCallScreeningRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
                !rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
            ) {
                callScreeningRoleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (!grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Tum izinler verilmedi. Uygulama duzgun calismayabilir.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
