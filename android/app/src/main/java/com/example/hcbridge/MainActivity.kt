package com.example.hcbridge

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var repo: HealthRepository
    private lateinit var status: TextView

    private val requestPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
            lifecycleScope.launch { refreshStatus() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = HealthRepository(this)

        status = TextView(this)
        val grant = Button(this).apply {
            text = "Grant Health Connect access"
            setOnClickListener { requestPermissions.launch(repo.permissions) }
        }
        val sync = Button(this).apply {
            text = "Sync now"
            setOnClickListener {
                WorkManager.getInstance(this@MainActivity)
                    .enqueue(OneTimeWorkRequestBuilder<SyncWorker>().build())
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 72, 36, 36)
            addView(status)
            addView(grant)
            addView(sync)
        }
        setContentView(layout)

        schedulePeriodicSync()
        lifecycleScope.launch { refreshStatus() }
    }

    private suspend fun refreshStatus() {
        val granted = repo.client.permissionController.getGrantedPermissions()
        val missing = repo.permissions - granted
        status.text = if (missing.isEmpty()) {
            "Health Connect permissions granted."
        } else {
            "Missing ${missing.size} permission(s)."
        }
    }

    private fun schedulePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "health-connect-sync",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
