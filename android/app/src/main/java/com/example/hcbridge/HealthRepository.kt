package com.example.hcbridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass

class HealthRepository(context: Context) {
    val client: HealthConnectClient = HealthConnectClient.getOrCreate(context)

    internal val recordTypes: Set<KClass<out Record>> = linkedSetOf(
        StepsRecord::class,
        HeartRateRecord::class,
        RestingHeartRateRecord::class,
        SleepSessionRecord::class,
        WeightRecord::class,
        BodyFatRecord::class,
        ExerciseSessionRecord::class,
        BloodPressureRecord::class,
    )

    val metricPermissions: Set<String> = recordTypes.map { HealthPermission.getReadPermission(it) }.toSet()

    val permissions: Set<String> = metricPermissions + setOf(
        HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
    )

    suspend fun readWindow(start: Instant, end: Instant): List<NormalizedMetric> {
        val out = mutableListOf<NormalizedMetric>()

        for (recordType in recordTypes) {
            recoverReadPaged(recordType, start, end).forEach { addRecord(it, out) }
        }

        return out
    }

    internal fun normalizeRecords(records: Iterable<Record>): List<NormalizedMetric> {
        val out = mutableListOf<NormalizedMetric>()
        records.forEach { addRecord(it, out) }
        return out
    }

    private fun addRecord(record: Record, out: MutableList<NormalizedMetric>) {
        when (record) {
            is StepsRecord -> {
                if (record.startTime.isBefore(record.endTime)) {
                    out += NormalizedMetric(
                        metric = "steps",
                        startTime = record.startTime.toString(),
                        endTime = record.endTime.toString(),
                        value = record.count.toDouble(),
                        unit = "count",
                        sourcePackage = record.metadata.dataOrigin.packageName,
                    )
                }
            }

            is HeartRateRecord -> {
                record.samples.forEach { sample ->
                    out += NormalizedMetric(
                        metric = "heart_rate",
                        startTime = sample.time.toString(),
                        value = sample.beatsPerMinute.toDouble(),
                        unit = "bpm",
                        sourcePackage = record.metadata.dataOrigin.packageName,
                    )
                }
            }

            is RestingHeartRateRecord -> {
                out += NormalizedMetric(
                    metric = "resting_heart_rate",
                    startTime = record.time.toString(),
                    value = record.beatsPerMinute.toDouble(),
                    unit = "bpm",
                    sourcePackage = record.metadata.dataOrigin.packageName,
                )
            }

            is WeightRecord -> {
                out += NormalizedMetric(
                    metric = "weight",
                    startTime = record.time.toString(),
                    value = record.weight.inKilograms,
                    unit = "kg",
                    sourcePackage = record.metadata.dataOrigin.packageName,
                )
            }

            is BodyFatRecord -> {
                out += NormalizedMetric(
                    metric = "body_fat",
                    startTime = record.time.toString(),
                    value = record.percentage.value,
                    unit = "percent",
                    sourcePackage = record.metadata.dataOrigin.packageName,
                )
            }

            is BloodPressureRecord -> {
                val systolic = record.systolic.inMillimetersOfMercury
                val diastolic = record.diastolic.inMillimetersOfMercury
                out += NormalizedMetric(
                    metric = "blood_pressure",
                    startTime = record.time.toString(),
                    value = systolic,
                    unit = "mmHg",
                    sourcePackage = record.metadata.dataOrigin.packageName,
                    metadata = mapOf(
                        "systolic" to systolic,
                        "diastolic" to diastolic,
                        "bodyPosition" to record.bodyPosition,
                        "measurementLocation" to record.measurementLocation,
                    ),
                )
            }

            is SleepSessionRecord -> {
                if (record.startTime.isBefore(record.endTime)) {
                    out += NormalizedMetric(
                        metric = "sleep_session",
                        startTime = record.startTime.toString(),
                        endTime = record.endTime.toString(),
                        sourcePackage = record.metadata.dataOrigin.packageName,
                        metadata = mapOf(
                            "durationMinutes" to Duration.between(record.startTime, record.endTime).toMinutes(),
                            "title" to record.title,
                            "notes" to record.notes,
                        ),
                    )
                }
            }

            is ExerciseSessionRecord -> {
                if (record.startTime.isBefore(record.endTime)) {
                    out += NormalizedMetric(
                        metric = "exercise_session",
                        startTime = record.startTime.toString(),
                        endTime = record.endTime.toString(),
                        sourcePackage = record.metadata.dataOrigin.packageName,
                        metadata = mapOf(
                            "durationMinutes" to Duration.between(record.startTime, record.endTime).toMinutes(),
                            "exerciseType" to record.exerciseType,
                            "title" to record.title,
                            "notes" to record.notes,
                        ),
                    )
                }
            }

            else -> Unit
        }
    }

    private suspend fun <T : Record> recoverReadPaged(
        recordType: KClass<T>,
        start: Instant,
        end: Instant,
    ): List<T> {
        if (!start.isBefore(end)) return emptyList()

        return try {
            readPaged(recordType, start, end)
        } catch (_: SecurityException) {
            // Missing one optional record permission must not block every other
            // metric. The UI reports partial authorization and can request it.
            emptyList()
        } catch (_: IllegalArgumentException) {
            // Some providers have written malformed interval records. Narrow the
            // request until only the bad second is skipped, keeping nearby valid
            // records and all other metric types available.
            val span = Duration.between(start, end)
            if (span <= MIN_RECOVERY_WINDOW) {
                emptyList()
            } else {
                val midpoint = start.plusMillis(span.toMillis() / 2)
                (recoverReadPaged(recordType, start, midpoint) +
                    recoverReadPaged(recordType, midpoint, end))
                    .distinctBy { it.metadata.id }
            }
        }
    }

    private suspend fun <T : Record> readPaged(
        recordType: KClass<T>,
        start: Instant,
        end: Instant,
    ): List<T> {
        val all = mutableListOf<T>()
        var pageToken: String? = null

        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = recordType,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageToken = pageToken,
                ),
            )
            all += response.records
            pageToken = response.pageToken
        } while (pageToken != null)

        return all
    }

    companion object {
        private val MIN_RECOVERY_WINDOW: Duration = Duration.ofSeconds(1)
    }
}
