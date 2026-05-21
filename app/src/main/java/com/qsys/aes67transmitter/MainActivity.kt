package com.qsys.aes67transmitter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private var transmitter: RtpTransmitter? = null
    private val handler = Handler(Looper.getMainLooper())

    // UI
    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var spinnerChannels: Spinner
    private lateinit var spinnerPtime: Spinner
    private lateinit var btnToggle: Button
    private lateinit var levelBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvSdp: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etIp           = findViewById(R.id.etIp)
        etPort         = findViewById(R.id.etPort)
        spinnerChannels = findViewById(R.id.spinnerChannels)
        spinnerPtime   = findViewById(R.id.spinnerPtime)
        btnToggle      = findViewById(R.id.btnToggle)
        levelBar       = findViewById(R.id.levelBar)
        tvStatus       = findViewById(R.id.tvStatus)
        tvSdp          = findViewById(R.id.tvSdp)

        // Spinners
        spinnerChannels.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Mono (1ch)", "Stereo (2ch)"))

        spinnerPtime.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("1ms", "4ms", "10ms"))

        btnToggle.setOnClickListener { toggleTransmit() }
        requestMicPermission()
    }

    private fun toggleTransmit() {
        if (transmitter?.running == true) {
            stopTransmit()
        } else {
            startTransmit()
        }
    }

    private fun startTransmit() {
        val ip   = etIp.text.toString().trim()
        val port = etPort.text.toString().toIntOrNull() ?: 5004
        val ch   = if (spinnerChannels.selectedItemPosition == 0) 1 else 2
        val ptime = when (spinnerPtime.selectedItemPosition) {
            0 -> 1; 1 -> 4; else -> 10
        }

        if (ip.isEmpty()) {
            tvStatus.text = "שגיאה: הכנס IP של Q-SYS"
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            tvStatus.text = "אין הרשאת מיקרופון"
            return
        }

        transmitter = RtpTransmitter(
            destIp = ip,
            destPort = port,
            channels = ch,
            packetTimeMs = ptime,
            onLevelUpdate = { level ->
                handler.post {
                    levelBar.progress = (level * 100).toInt()
                }
            }
        )

        Thread {
            try {
                transmitter?.start()
            } catch (e: Exception) {
                handler.post {
                    tvStatus.text = "שגיאה: ${e.message}"
                    updateUI(false)
                }
            }
        }.start()

        updateUI(true)
        tvSdp.text = transmitter?.generateSdp() ?: ""
        tvStatus.text = "שידור פעיל → $ip:$port"
    }

    private fun stopTransmit() {
        Thread { transmitter?.stop() }.start()
        transmitter = null
        updateUI(false)
        levelBar.progress = 0
        tvStatus.text = "מוכן"
        tvSdp.text = ""
    }

    private fun updateUI(running: Boolean) {
        btnToggle.text = if (running) "⏹ עצור שידור" else "▶ התחל שידור"
        btnToggle.setBackgroundColor(
            if (running) getColor(android.R.color.holo_red_dark)
            else getColor(android.R.color.holo_green_dark)
        )
        etIp.isEnabled = !running
        etPort.isEnabled = !running
        spinnerChannels.isEnabled = !running
        spinnerPtime.isEnabled = !running
    }

    private fun requestMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        transmitter?.stop()
    }
}
