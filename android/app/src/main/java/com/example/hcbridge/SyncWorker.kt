package com.example.hcbridge

import android.content.Context
import androidx.health.connect.client.permission.HealthPermission
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.temporal.ChronoUnit

class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val repo = HealthRepository(applicationContext)
            val granted = repo.client.permissionController.getGrantedPermissions()

            val hasBackgroundPermission =
                HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted
            val hasAnyMetricPermission = repo.metricPermissions.any { it in granted }

            if (!hasBackgroundPermission || !hasAnyMetricPermission) {
                return Result.retry()
            }

            val end = Instant.now()
            val start = end.minus(1, ChronoUnit.DAYS)
            val rows = repo.readWindow(start, end)

            NeonBackendClient(applicationContext).sync(rows)
            Result.success()

        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Result.retry()
        }
    }
}
