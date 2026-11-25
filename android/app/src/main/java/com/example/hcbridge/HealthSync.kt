package com.example.hcbridge

import android.content.Context
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ChangesTokenRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.reflect.KClass

internal class HealthSync(
    context: Context,
    private val repo: HealthRepository,
) {
    private val preferences = context.getSharedPreferences("hcbridge_sync", Context.MODE_PRIVATE)
    private val backend = NeonBackendClient(context.applicationContext)

    suspend fun syncRecentHistory(): SyncResult = mutex.withLock {
        val end = Instant.now()
        backend.sync(repo.readWindow(end.minus(7, ChronoUnit.DAYS), end))
    }

    suspend fun syncChanges() = mutex.withLock {
        val granted = repo.client.permissionController.getGrantedPermissions()
        val recordTypes = repo.recordTypes.filterTo(mutableSetOf()) {
            HealthPermission.getReadPermission(it) in granted
        }
        if (recordTypes.isEmpty()) return@withLock

        val recordTypeKey = recordTypes.map { HealthPermission.getReadPermission(it) }
            .sorted()
            .joinToString(",")
        val savedPermissions = preferences.getString("permissions", null)
        val savedToken = preferences.getString("changes_token", null)
        if (savedToken == null || savedPermissions != recordTypeKey) {
            resetChangesToken(recordTypes, recordTypeKey)
            return@withLock
        }

        var token: String = savedToken
        do {
            val page = try {
                repo.client.getChanges(token)
            } catch (_: IllegalArgumentException) {
                // A malformed provider record can prevent the change page from being decoded.
                // The window reader recovers the valid records around it.
                resetChangesToken(recordTypes, recordTypeKey)
                return@withLock
            }
            if (page.changesTokenExpired) {
                resetChangesToken(recordTypes, recordTypeKey)
                return@withLock
            }

            val records = page.changes.filterIsInstance<UpsertionChange>().map { it.record }
            val rows = repo.normalizeRecords(records)
            if (rows.isNotEmpty()) backend.sync(rows)

            // Advance only after the upload has succeeded so a retry can replay the page.
            token = page.nextChangesToken
            saveChangesToken(token, recordTypeKey)
        } while (page.hasMore)
    }

    private suspend fun resetChangesToken(
        recordTypes: Set<KClass<out Record>>,
        recordTypeKey: String,
    ) {
        // Capture changes that arrive while the initial snapshot is being read or uploaded.
        val token = repo.client.getChangesToken(ChangesTokenRequest(recordTypes))
        val end = Instant.now()
        backend.sync(repo.readWindow(end.minus(1, ChronoUnit.DAYS), end))
        saveChangesToken(token, recordTypeKey)
    }

    private fun saveChangesToken(token: String, recordTypeKey: String) {
        preferences.edit()
            .putString("changes_token", token)
            .putString("permissions", recordTypeKey)
            .apply()
    }

    companion object {
        private val mutex = Mutex()
    }
}
