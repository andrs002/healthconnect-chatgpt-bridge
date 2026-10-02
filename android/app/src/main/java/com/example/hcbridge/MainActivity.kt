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
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var repo: HealthRepository

    private lateinit var permissionStatus: TextView
    private lateinit var readStatus: TextView
    private lateinit var dataPreview: TextView
    private lateinit var backendStatus: TextView

    private var lastRows: List<NormalizedMetric> = emptyList()

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
            text = "HC Bridge — Health Connect Reader ${BuildConfig.VERSION_NAME}"
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
            text = "Backend: Neon Data API configured; no upload attempted yet."
            textSize = 16f
        }

        val syncButton = Button(this).apply {
            text = "SYNC LAST 7 DAYS TO NEON"
            setOnClickListener {
                lifecycleScope.launch {
                    syncNow()
                }
            }
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

        schedulePeriodicSync()
    }

    private suspend fun refreshPermissionStatus() {
        try {
            val granted = repo.client.permissionController.getGrantedPermissions()
            val missing = repo.permissions - granted
            val grantedMetricCount = repo.metricPermissions.count { it in granted }

            permissionStatus.text =
                if (missing.isEmpty()) {
                    "Health Connect: AUTHORIZED"
                } else if (grantedMetricCount > 0) {
                    "Health Connect: PARTIAL — $grantedMetricCount/${repo.metricPermissions.size} data type(s) authorized; tap GRANT to add the missing permissions."
                } else {
                    "Health Connect: NOT AUTHORIZED — tap GRANT HEALTH CONNECT ACCESS."
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
            val grantedMetricPermissions = repo.metricPermissions.intersect(granted)

            if (grantedMetricPermissions.isEmpty()) {
                readStatus.text =
                    "Local read: BLOCKED — grant at least one Health Connect data permission first."
                return
            }

            val end = Instant.now()
            val start = end.minus(7, ChronoUnit.DAYS)

            val rows = repo.readWindow(start, end)
            lastRows = rows

            if (rows.isEmpty()) {
                readStatus.text =
                    "Local read: SUCCESS, but Health Connect returned 0 records in the last 7 days."
                dataPreview.text =
                    "This proves the API call worked, but no matching records were returned."
                return
            }

            val summary = buildHealthPreview(rows)
            val missingMetricCount = repo.metricPermissions.size - grantedMetricPermissions.size

            readStatus.text =
                if (missingMetricCount == 0) {
                    "Local read: SUCCESS — ${rows.size} Health Connect record(s) read from this phone."
                } else {
                    "Local read: PARTIAL SUCCESS — ${rows.size} record(s) read; $missingMetricCount data type permission(s) still missing."
                }
            dataPreview.text = summary

        } catch (t: Throwable) {
            readStatus.text =
                "Local read: FAILED — ${t.javaClass.simpleName}: ${t.message}"
            dataPreview.text = t.stackTraceToString()
        }
    }

    private suspend fun syncNow() {
        backendStatus.text = "Backend: syncing..."

        try {
            val granted = repo.client.permissionController.getGrantedPermissions()
            val grantedMetricPermissions = repo.metricPermissions.intersect(granted)

            if (grantedMetricPermissions.isEmpty()) {
                backendStatus.text =
                    "Backend: BLOCKED — grant at least one Health Connect data permission first."
                return
            }

            val rows =
                if (lastRows.isNotEmpty()) {
                    lastRows
                } else {
                    val end = Instant.now()
                    val start = end.minus(7, ChronoUnit.DAYS)
                    repo.readWindow(start, end)
                }

            val result = NeonBackendClient(applicationContext).sync(rows)
            val missingMetricCount = repo.metricPermissions.size - grantedMetricPermissions.size

            backendStatus.text =
                if (missingMetricCount == 0) {
                    "Backend: SUCCESS — submitted ${result.submitted} record(s) to Neon. userId=${result.userId}"
                } else {
                    "Backend: PARTIAL SUCCESS — submitted ${result.submitted} record(s); $missingMetricCount data type permission(s) missing. userId=${result.userId}"
                }

        } catch (t: Throwable) {
            backendStatus.text =
                "Backend: FAILED — ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun schedulePeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest =
            PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS
                )
                .build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniquePeriodicWork(
                "hcbridge-sync",
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
    }

    private fun buildHealthPreview(rows: List<NormalizedMetric>): String {
        val grouped = rows.groupBy { it.metric }

        return buildString {
            appendLine("LATEST VALUES — ASIA/TAIPEI")
            appendLine("---------------------------")
            METRIC_ORDER.forEach { metric ->
                val latest = grouped[metric]?.maxByOrNull { it.startTime }
                append(METRIC_LABELS.getValue(metric))
                append(": ")
                if (latest == null) {
                    appendLine("no record in the last 7 days")
                } else {
                    append(formatMetricValue(latest))
                    append(" | ")
                    append(formatTaipeiTime(latest.startTime))
                    appendLine()
                }
            }

            appendLine()
            appendLine("RECORD COUNTS")
            appendLine("-------------")
            appendLine("total: ${rows.size}")
            METRIC_ORDER.forEach { metric ->
                appendLine("${METRIC_LABELS.getValue(metric)}: ${grouped[metric]?.size ?: 0}")
            }

            appendLine()
            appendLine("LATEST SAMPLE RECORDS")
            appendLine("---------------------")
            rows.sortedByDescending { it.startTime }
                .take(25)
                .forEach { row ->
                    append(METRIC_LABELS[row.metric] ?: row.metric)
                    append(" | ")
                    append(formatTaipeiTime(row.startTime))
                    append(" | ")
                    append(formatMetricValue(row))
                    if (!row.sourcePackage.isNullOrBlank()) {
                        append(" | source=")
                        append(row.sourcePackage)
                    }
                    appendLine()
                }
        }
    }

    private fun formatMetricValue(row: NormalizedMetric): String {
        if (row.metric == "blood_pressure") {
            val systolic = (row.metadata["systolic"] as? Number)?.toDouble() ?: row.value
            val diastolic = (row.metadata["diastolic"] as? Number)?.toDouble()
            if (systolic != null && diastolic != null) {
                return "${formatNumber(systolic)}/${formatNumber(diastolic)} mmHg"
            }
        }

        if (row.metric == "sleep_session" || row.metric == "exercise_session") {
            val minutes = durationMinutes(row)
            if (minutes != null) {
                return if (row.metric == "sleep_session") {
                    "${minutes / 60}h ${minutes % 60}m"
                } else {
                    "$minutes min"
                }
            }
        }

        val value = row.value ?: return "record present"
        return buildString {
            append(formatNumber(value))
            if (!row.unit.isNullOrBlank()) {
                append(" ")
                append(row.unit)
            }
        }
    }

    private fun durationMinutes(row: NormalizedMetric): Long? {
        val metadataValue = row.metadata["durationMinutes"] as? Number
        if (metadataValue != null) return metadataValue.toLong()

        val endTime = row.endTime ?: return null
        return runCatching {
            Duration.between(Instant.parse(row.startTime), Instant.parse(endTime)).toMinutes()
        }.getOrNull()
    }

    private fun formatTaipeiTime(value: String): String =
        runCatching {
            TAIPEI_TIME_FORMAT.format(Instant.parse(value).atZone(TAIPEI_ZONE))
        }.getOrDefault(value)

    private fun formatNumber(value: Double): String =
        if (value % 1.0 == 0.0) {
            value.toLong().toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }

    private fun LinearLayout.addSpacer() {
        addView(TextView(context).apply { text = "\n" })
    }

    companion object {
        private val TAIPEI_ZONE: ZoneId = ZoneId.of("Asia/Taipei")
        private val TAIPEI_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        private val METRIC_ORDER = listOf(
            "steps",
            "heart_rate",
            "resting_heart_rate",
            "blood_pressure",
            "sleep_session",
            "weight",
            "body_fat",
            "exercise_session",
        )

        private val METRIC_LABELS = mapOf(
            "steps" to "Steps (latest interval)",
            "heart_rate" to "Heart rate",
            "resting_heart_rate" to "Resting heart rate",
            "blood_pressure" to "Blood pressure",
            "sleep_session" to "Sleep duration",
            "weight" to "Weight",
            "body_fat" to "Body fat",
            "exercise_session" to "Exercise duration",
        )
    }
}
