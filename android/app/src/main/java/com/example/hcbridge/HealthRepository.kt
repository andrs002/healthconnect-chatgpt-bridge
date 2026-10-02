package com.example.hcbridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant

class HealthRepository(context: Context) {
    val client: HealthConnectClient = HealthConnectClient.getOrCreate(context)
    val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class), HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class), HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class), HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class), HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
    )

    suspend fun readWindow(start: Instant, end: Instant): List<NormalizedMetric> {
        val out = mutableListOf<NormalizedMetric>()
        recoverSteps(start,end).forEach { r -> if(r.startTime.isBefore(r.endTime)) out += NormalizedMetric(metric="steps",startTime=r.startTime.toString(),endTime=r.endTime.toString(),value=r.count.toDouble(),unit="count",sourcePackage=r.metadata.dataOrigin.packageName) }
        safeReadPaged(HeartRateRecord::class,start,end).forEach { r -> r.samples.forEach { s -> out += NormalizedMetric(metric="heart_rate",startTime=s.time.toString(),value=s.beatsPerMinute.toDouble(),unit="bpm",sourcePackage=r.metadata.dataOrigin.packageName) } }
        safeReadPaged(RestingHeartRateRecord::class,start,end).forEach { r -> out += NormalizedMetric(metric="resting_heart_rate",startTime=r.time.toString(),value=r.beatsPerMinute.toDouble(),unit="bpm",sourcePackage=r.metadata.dataOrigin.packageName) }
        safeReadPaged(WeightRecord::class,start,end).forEach { r -> out += NormalizedMetric(metric="weight",startTime=r.time.toString(),value=r.weight.inKilograms,unit="kg",sourcePackage=r.metadata.dataOrigin.packageName) }
        safeReadPaged(BodyFatRecord::class,start,end).forEach { r -> out += NormalizedMetric(metric="body_fat",startTime=r.time.toString(),value=r.percentage.value,unit="percent",sourcePackage=r.metadata.dataOrigin.packageName) }
        safeReadPaged(SleepSessionRecord::class,start,end).forEach { r -> if(r.startTime.isBefore(r.endTime)) out += NormalizedMetric(metric="sleep_session",startTime=r.startTime.toString(),endTime=r.endTime.toString(),sourcePackage=r.metadata.dataOrigin.packageName,metadata=mapOf("title" to r.title,"notes" to r.notes)) }
        safeReadPaged(ExerciseSessionRecord::class,start,end).forEach { r -> if(r.startTime.isBefore(r.endTime)) out += NormalizedMetric(metric="exercise_session",startTime=r.startTime.toString(),endTime=r.endTime.toString(),sourcePackage=r.metadata.dataOrigin.packageName,metadata=mapOf("exerciseType" to r.exerciseType,"title" to r.title,"notes" to r.notes)) }
        return out
    }

    private suspend fun recoverSteps(start: Instant,end: Instant): List<StepsRecord> {
        if(!start.isBefore(end)) return emptyList()
        return try { readPaged(StepsRecord::class,start,end) }
        catch(e: IllegalArgumentException) {
            val span=Duration.between(start,end)
            if(span <= Duration.ofMinutes(1)) emptyList()
            else { val mid=start.plusMillis(span.toMillis()/2); recoverSteps(start,mid)+recoverSteps(mid,end) }
        }
    }

    private suspend fun <T:Record> safeReadPaged(klass:kotlin.reflect.KClass<T>,start:Instant,end:Instant):List<T> = try { readPaged(klass,start,end) } catch(e:IllegalArgumentException) { emptyList() }

    private suspend fun <T:Record> readPaged(klass:kotlin.reflect.KClass<T>,start:Instant,end:Instant):List<T> {
        val all=mutableListOf<T>(); var token:String?=null
        do { val response=client.readRecords(ReadRecordsRequest(recordType=klass,timeRangeFilter=TimeRangeFilter.between(start,end),pageToken=token)); all+=response.records; token=response.pageToken } while(token!=null)
        return all
    }
}
