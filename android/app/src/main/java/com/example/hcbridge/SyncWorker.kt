package com.example.hcbridge

import android.content.Context
import androidx.health.connect.client.permission.HealthPermission
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

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
                return Result.success()
            }

            HealthSync(applicationContext, repo).syncChanges()
            Result.success()

        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Result.retry()
        }
    }
}
