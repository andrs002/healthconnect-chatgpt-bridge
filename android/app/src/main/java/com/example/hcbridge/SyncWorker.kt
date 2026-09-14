package com.example.hcbridge

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
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

            // MVP: sync the last 24h each run; production should use Changes API/tokens.
            val end = Instant.now()
            val start = end.minus(1, ChronoUnit.DAYS)
            val rows = repo.readWindow(start, end)

            val retrofit = Retrofit.Builder()
                .baseUrl(BACKEND_BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            retrofit.create(BridgeApi::class.java).ingest(
                authorization = "Bearer $USER_ACCESS_TOKEN",
                body = IngestRequest(USER_ID, rows)
            )
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }

    companion object {
        const val BACKEND_BASE_URL = "https://YOUR-BACKEND.example/"
        const val USER_ACCESS_TOKEN = "REPLACE_WITH_REAL_LOGIN_TOKEN"
        const val USER_ID = "replace-user-id"
    }
}
