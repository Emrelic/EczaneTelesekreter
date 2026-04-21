package com.eczane.nobetci.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.eczane.nobetci.R
import com.eczane.nobetci.util.PrefsManager
import com.eczane.nobetci.util.TtsManager

/**
 * Yapay zeka sesiyle mesaj oluşturma ekranı.
 * Kullanıcı metni yazar, yapay zeka Türkçe olarak okur ve ses dosyası olarak kaydeder.
 */
class TtsMessageActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager
    private lateinit var ttsManager: TtsManager
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var etMesajMetni: EditText
    private lateinit var btnSablonOlustur: Button
    private lateinit var btnDinle: Button
    private lateinit var btnKaydet: Button
    private lateinit var tvStatus: TextView
    private lateinit var seekBarHiz: SeekBar
    private lateinit var tvHiz: TextView
    private lateinit var seekBarTon: SeekBar
    private lateinit var tvTon: TextView

    private var currentRate = 0.9f
    private var currentPitch = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tts_message)

        prefs = PrefsManager(this)
        initViews()
        initTts()

        supportActionBar?.apply {
            title = "Yapay Zeka ile Ses Oluştur"
            setDisplayHomeAsUpEnabled(true)
        }
    }

    private fun initViews() {
        etMesajMetni = findViewById(R.id.etMesajMetni)
        btnSablonOlustur = findViewById(R.id.btnSablonOlustur)
        btnDinle = findViewById(R.id.btnDinle)
        btnKaydet = findViewById(R.id.btnKaydet)
        tvStatus = findViewById(R.id.tvStatus)
        seekBarHiz = findViewById(R.id.seekBarHiz)
        tvHiz = findViewById(R.id.tvHiz)
        seekBarTon = findViewById(R.id.seekBarTon)
        tvTon = findViewById(R.id.tvTon)

        // Başlangıçta butonları devre dışı bırak (TTS hazır olana kadar)
        btnDinle.isEnabled = false
        btnKaydet.isEnabled = false

        // Şablon oluştur butonu
        btnSablonOlustur.setOnClickListener {
            generateTemplate()
        }

        // Dinle butonu
        btnDinle.setOnClickListener {
            val text = etMesajMetni.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Lütfen bir mesaj yazın", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tvStatus.text = "Dinleniyor..."
            ttsManager.speak(text)
        }

        // Kaydet butonu
        btnKaydet.setOnClickListener {
            val text = etMesajMetni.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Lütfen bir mesaj yazın", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveAsAudio(text)
        }

        // Hız ayarı (SeekBar: 0-20, gerçek değer: 0.5 - 1.5)
        seekBarHiz.progress = 8 // 0.9 varsayılan
        tvHiz.text = "Konuşma Hızı: 0.9x"
        seekBarHiz.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentRate = 0.5f + (progress * 0.05f)
                tvHiz.text = "Konuşma Hızı: %.1fx".format(currentRate)
                ttsManager.setSpeechRate(currentRate)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Ton ayarı (SeekBar: 0-20, gerçek değer: 0.5 - 1.5)
        seekBarTon.progress = 10 // 1.0 varsayılan
        tvTon.text = "Ses Tonu: 1.0x"
        seekBarTon.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentPitch = 0.5f + (progress * 0.05f)
                tvTon.text = "Ses Tonu: %.1fx".format(currentPitch)
                ttsManager.setPitch(currentPitch)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Eğer kayıtlı eczane bilgisi varsa, şablonu otomatik oluştur
        if (prefs.eczaneAdi.isNotEmpty()) {
            generateTemplate()
        }
    }

    private fun initTts() {
        tvStatus.text = "Ses motoru yükleniyor..."
        ttsManager = TtsManager(
            context = this,
            onReady = {
                handler.post {
                    tvStatus.text = "Hazır - Mesajınızı yazın veya şablon oluşturun"
                    btnDinle.isEnabled = true
                    btnKaydet.isEnabled = true
                }
            },
            onError = { error ->
                handler.post {
                    tvStatus.text = "Hata: $error"
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                }
            }
        )
        ttsManager.initialize()
    }

    /**
     * Kayıtlı eczane bilgilerinden otomatik mesaj şablonu oluştur
     */
    private fun generateTemplate() {
        val adi = prefs.eczaneAdi.ifEmpty { "___" }
        val adres = prefs.eczaneAdres.ifEmpty { "___" }

        val template = buildString {
            append("Merhaba, Nöbetçi $adi'ni aradınız. ")
            append("Sabah dokuz sıfır sıfıra kadar açığız. ")
            append("$adres. ")
            append("Eczanemizin konumunu WhatsApp'tan telefonunuza gönderiyoruz. ")
            append("Mesajı tekrar dinlemek için, bir'e basın. ")
            append("Eczaneye bağlanmak için, iki'ye basın.")
        }

        etMesajMetni.setText(template)
        Toast.makeText(this, "Sablon olusturuldu - duzenleyebilirsiniz", Toast.LENGTH_SHORT).show()
    }

    /**
     * Metni ses dosyası olarak kaydet
     */
    private fun saveAsAudio(text: String) {
        tvStatus.text = "Ses dosyası oluşturuluyor..."
        btnKaydet.isEnabled = false
        btnDinle.isEnabled = false

        ttsManager.saveToFile(
            text = text,
            onComplete = { filePath ->
                handler.post {
                    // PrefsManager'a kaydet
                    prefs.audioFilePath = filePath
                    prefs.usesCustomAudio = true

                    tvStatus.text = "Ses dosyası başarıyla kaydedildi!"
                    btnKaydet.isEnabled = true
                    btnDinle.isEnabled = true

                    Toast.makeText(
                        this,
                        "Yapay zeka ses mesajı kaydedildi ve aktif edildi!",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            onSaveError = { error ->
                handler.post {
                    tvStatus.text = "Hata: $error"
                    btnKaydet.isEnabled = true
                    btnDinle.isEnabled = true

                    Toast.makeText(this, "Kayıt hatası: $error", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    override fun onSupportNavigateUp(): Boolean {
        ttsManager.stopSpeaking()
        finish()
        return true
    }

    override fun onDestroy() {
        ttsManager.shutdown()
        super.onDestroy()
    }
}
