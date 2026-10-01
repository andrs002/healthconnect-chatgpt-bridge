package com.example.hcbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.File

class RadioWatchdogActivity : ComponentActivity() {
    private lateinit var status: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updateStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "S22 LTE Watchdog"
            textSize = 22f
        }
        status = TextView(this).apply { textSize = 15f }

        val grant = Button(this).apply {
            text = "GRANT RADIO DIAGNOSTIC PERMISSIONS"
            setOnClickListener { requestNeededPermissions() }
        }
        val start = Button(this).apply {
            text = "START WATCHDOG"
            setOnClickListener {
                if (!hasCorePermissions()) {
                    requestNeededPermissions()
                } else {
                    ContextCompat.startForegroundService(
                        this@RadioWatchdogActivity,
                        Intent(this@RadioWatchdogActivity, RadioWatchdogService::class.java)
                    )
                    status.postDelayed({ updateStatus() }, 500)
                }
            }
        }
        val stop = Button(this).apply {
            text = "STOP WATCHDOG"
            setOnClickListener {
                stopService(Intent(this@RadioWatchdogActivity, RadioWatchdogService::class.java))
                status.postDelayed({ updateStatus() }, 300)
            }
        }
        val share = Button(this).apply {
            text = "SHARE / EXPORT WATCHDOG LOG"
            setOnClickListener { shareLog() }
        }

        val note = TextView(this).apply {
            text = "Purpose: capture real S22 LTE deregistration, serving-cell changes and recovery time. This app does not claim to control or repair Samsung/Qualcomm modem firmware. It avoids forced airplane-mode/radio resets so the failure evidence is preserved."
            textSize = 14f
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 48)
            addView(title)
            addView(status)
            addView(grant)
            addView(start)
            addView(stop)
            addView(share)
            addView(note)
        })
        updateStatus()
    }

    private fun hasCorePermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestNeededPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun updateStatus() {
        val file = RadioWatchdogService.logFile(this)
        status.text = buildString {
            append("Phone/location permission: ")
            append(if (hasCorePermissions()) "OK" else "REQUIRED")
            append("\nLog: ")
            if (file.exists()) append("${file.length()} bytes") else append("not created yet")
            append("\n\nWhen enabled, keep the persistent ‘S22 LTE Watchdog’ notification present while testing.")
        }
    }

    private fun shareLog() {
        val source = RadioWatchdogService.logFile(this)
        if (!source.exists() || source.length() == 0L) {
            status.text = "No watchdog log exists yet. Start the watchdog first."
            return
        }
        // Copy into cache and expose via a content URI through FileProvider.
        val out = File(cacheDir, "s22-radio-watchdog.csv")
        source.copyTo(out, overwrite = true)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "${packageName}.files",
            out
        )
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share S22 LTE watchdog log"))
    }
}
