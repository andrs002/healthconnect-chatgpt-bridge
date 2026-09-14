package com.example.hcbridge

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit

class MainActivity : ComponentActivity() {

    private lateinit var repo: HealthRepository

    private lateinit var permissionStatus: TextView
    private lateinit var readStatus: TextView
    private lateinit var dataPreview: TextView
    private lateinit var backendStatus: TextView

    private val requestPermissions =
        registerForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) {
            lifecycleScope.launch {
                refreshPermissionStatus()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repo = HealthRepository(this)

        val title = TextView(this).apply {
            text = "HC Bridge — Health Connect Reader"
            textSize = 22f
        }

        permissionStatus = TextView(this).apply {
            text = "Health Connect: checking..."
            textSize = 16f
        }

        val grantButton = Button(this).apply {
            text = "GRANT HEALTH CONNECT ACCESS"
            setOnClickListener {
                requestPermissions.launch(repo.permissions)
            }
        }

        val readButton = Button(this).apply {
            text = "READ LAST 7 DAYS NOW"
            setOnClickListener {
                lifecycleScope.launch {
                    readHealthData()
                }
            }
        }

        readStatus = TextView(this).apply {
            text = "Local read: not tested yet"
            textSize = 16f
        }

        dataPreview = TextView(this).apply {
            text = "No Health Connect records loaded yet."
            textSize = 14f
            setTextIsSelectable(true)
        }

        backendStatus = TextView(this).apply {
            text = "Backend: NOT CONFIGURED — no health data is being uploaded anywhere."
            textSize = 16f
        }

        val syncButton = Button(this).apply {
            text = "SYNC TO BACKEND (DISABLED)"
            isEnabled = false
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 48)

            addView(title)
            addSpacer()
            addView(permissionStatus)
            addView(grantButton)
            addSpacer()
            addView(readButton)
            addView(readStatus)
            addSpacer()
            addView(dataPreview)
            addSpacer()
            addView(backendStatus)
            addView(syncButton)
        }

        val scroll = ScrollView(this).apply {
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        setContentView(scroll)

        lifecycleScope.launch {
            refreshPermissionStatus()
        }
    }

    private suspend fun refreshPermissionStatus() {
        try {
            val granted = repo.client.permissionController.getGrantedPermissions()
            val missing = repo.permissions - granted

            permissionStatus.text =
                if (missing.isEmpty()) {
                    "Health Connect: AUTHORIZED"
                } else {
                    "Health Connect: NOT FULLY AUTHORIZED (${missing.size} permission(s) missing)"
                }
        } catch (t: Throwable) {
            permissionStatus.text =
                "Health Connect status error: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private suspend fun readHealthData() {
        readStatus.text = "Local read: reading..."
        dataPreview.text = ""

        try {
            val granted = repo.client.permissionController.getGrantedPermissions()
            val missing = repo.permissions - granted

            if (missing.isNotEmpty()) {
                readStatus.text =
                    "Local read: BLOCKED — grant Health Connect permissions first."
                return
            }

            val end = Instant.now()
            val start = end.minus(7, ChronoUnit.DAYS)

            val rows = repo.readWindow(start, end)

            if (rows.isEmpty()) {
                readStatus.text =
                    "Local read: SUCCESS, but Health Connect returned 0 records in the last 7 days."
                dataPreview.text =
                    "This proves the API call worked, but no matching records were returned."
                return
            }

            val grouped = rows.groupBy { it.metric }
            val summary = buildString {
                appendLine("TOTAL RECORDS: ${rows.size}")
                appendLine()

                grouped.toSortedMap().forEach { (metric, metricRows) ->
                    appendLine("$metric: ${metricRows.size}")
                }

                appendLine()
                appendLine("LATEST SAMPLE RECORDS")
                appendLine("---------------------")

                rows
                    .sortedByDescending { it.startTime }
                    .take(25)
                    .forEach { row ->
                        append(row.metric)
                        append(" | ")
                        append(row.startTime)

                        if (row.value != null) {
                            append(" | ")
                            append(row.value)
                            if (!row.unit.isNullOrBlank()) {
                                append(" ")
                                append(row.unit)
                            }
                        }

                        if (!row.sourcePackage.isNullOrBlank()) {
                            append(" | source=")
                            append(row.sourcePackage)
                        }

                        appendLine()
                    }
            }

            readStatus.text =
                "Local read: SUCCESS — ${rows.size} Health Connect record(s) read from this phone."
            dataPreview.text = summary

        } catch (t: Throwable) {
            readStatus.text =
                "Local read: FAILED — ${t.javaClass.simpleName}: ${t.message}"
            dataPreview.text = t.stackTraceToString()
        }
    }

    private fun LinearLayout.addSpacer() {
        addView(TextView(context).apply { text = "\n" })
    }
}
