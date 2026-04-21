package com.eczane.nobetci.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.eczane.nobetci.R
import com.eczane.nobetci.util.PrefsManager
import java.io.File

/**
 * Kullanıcının kendi ses mesajını kaydetmesini sağlayan ekran.
 */
class RecordMessageActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RecordMessage"
        private const val PERMISSION_REQUEST_CODE = 200
        private const val AUDIO_FILE_NAME = "nobetci_mesaj.m4a"
    }

    private lateinit var prefs: PrefsManager
    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isRecording = false
    private var isPlaying = false
    private lateinit var audioFilePath: String

    private lateinit var btnRecord: Button
    private lateinit var btnStop: Button
    private lateinit var btnPlay: Button
    private lateinit var btnSave: Button
    private lateinit var btnDelete: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvTimer: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var recordStartTime = 0L
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val elapsed = (System.currentTimeMillis() - recordStartTime) / 1000
                val minutes = elapsed / 60
                val seconds = elapsed % 60
                tvTimer.text = String.format("%02d:%02d", minutes, seconds)
                handler.postDelayed(this, 500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_record_message)

        prefs = PrefsManager(this)
        audioFilePath = "${filesDir.absolutePath}/$AUDIO_FILE_NAME"

        initViews()
        updateButtonStates()

        supportActionBar?.apply {
            title = "Ses Mesajı Kaydet"
            setDisplayHomeAsUpEnabled(true)
        }
    }

    private fun initViews() {
        btnRecord = findViewById(R.id.btnRecord)
        btnStop = findViewById(R.id.btnStop)
        btnPlay = findViewById(R.id.btnPlay)
        btnSave = findViewById(R.id.btnSave)
        btnDelete = findViewById(R.id.btnDelete)
        tvStatus = findViewById(R.id.tvStatus)
        tvTimer = findViewById(R.id.tvTimer)

        btnRecord.setOnClickListener { startRecording() }
        btnStop.setOnClickListener { stopRecording() }
        btnPlay.setOnClickListener { playRecording() }
        btnSave.setOnClickListener { saveRecording() }
        btnDelete.setOnClickListener { deleteRecording() }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_CODE
            )
            return
        }

        try {
            mediaRecorder = MediaRecorder(this).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(audioFilePath)
                prepare()
                start()
            }

            isRecording = true
            recordStartTime = System.currentTimeMillis()
            handler.post(timerRunnable)

            tvStatus.text = "🔴 Kayıt yapılıyor..."
            updateButtonStates()

            Log.d(TAG, "Kayıt başladı: $audioFilePath")

        } catch (e: Exception) {
            Log.e(TAG, "Kayıt başlatılamadı", e)
            Toast.makeText(this, "Kayıt başlatılamadı: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false
            handler.removeCallbacks(timerRunnable)

            tvStatus.text = "Kayıt tamamlandı"
            updateButtonStates()

            Log.d(TAG, "Kayıt durduruldu")

        } catch (e: Exception) {
            Log.e(TAG, "Kayıt durdurma hatası", e)
        }
    }

    private fun playRecording() {
        if (!File(audioFilePath).exists()) {
            Toast.makeText(this, "Ses dosyası bulunamadı", Toast.LENGTH_SHORT).show()
            return
        }

        if (isPlaying) {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            isPlaying = false
            btnPlay.text = "▶ Dinle"
            tvStatus.text = "Kayıt mevcut"
            return
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(audioFilePath)
                prepare()
                setOnCompletionListener {
                    this@RecordMessageActivity.isPlaying = false
                    btnPlay.text = "▶ Dinle"
                    tvStatus.text = "Kayıt mevcut"
                }
                start()
            }
            isPlaying = true
            btnPlay.text = "⏹ Durdur"
            tvStatus.text = "Dinleniyor..."

        } catch (e: Exception) {
            Log.e(TAG, "Oynatma hatası", e)
            Toast.makeText(this, "Ses çalınamadı: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveRecording() {
        if (!File(audioFilePath).exists()) {
            Toast.makeText(this, "Kaydedilecek ses dosyası yok", Toast.LENGTH_SHORT).show()
            return
        }

        prefs.audioFilePath = audioFilePath
        prefs.usesCustomAudio = true

        Toast.makeText(this, "Ses mesajı kaydedildi!", Toast.LENGTH_SHORT).show()
        tvStatus.text = "Ses mesajı aktif olarak kaydedildi"
    }

    private fun deleteRecording() {
        try {
            File(audioFilePath).delete()
            prefs.audioFilePath = null
            prefs.usesCustomAudio = false

            tvStatus.text = "Kayıt yok"
            tvTimer.text = "00:00"
            updateButtonStates()

            Toast.makeText(this, "Ses mesajı silindi", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Log.e(TAG, "Silme hatası", e)
        }
    }

    private fun updateButtonStates() {
        val hasRecording = File(audioFilePath).exists()
        btnRecord.isEnabled = !isRecording
        btnStop.isEnabled = isRecording
        btnPlay.isEnabled = hasRecording && !isRecording
        btnSave.isEnabled = hasRecording && !isRecording
        btnDelete.isEnabled = hasRecording && !isRecording

        if (!isRecording && hasRecording) {
            tvStatus.text = "Kayıt mevcut"
        } else if (!isRecording) {
            tvStatus.text = "Kayıt yok - Kaydet butonuna basın"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording()
            } else {
                Toast.makeText(this, "Mikrofon izni gereklidir", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        mediaRecorder?.release()
        mediaPlayer?.release()
        super.onDestroy()
    }
}
