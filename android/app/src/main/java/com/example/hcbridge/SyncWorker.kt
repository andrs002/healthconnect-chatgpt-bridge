package com.example.hcbridge

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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

            if (!granted.containsAll(repo.permissions)) {
                return Result.retry()
            }

            val end = Instant.now()
            val start = end.minus(1, ChronoUnit.DAYS)
            val rows = repo.readWindow(start, end)

            NeonBackendClient(applicationContext).sync(rows)
            Result.success()

        } catch (t: Throwable) {
            Result.retry()
        }
    }
}
