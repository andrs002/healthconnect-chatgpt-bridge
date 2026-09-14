package com.example.hcbridge

import android.content.Context
import com.google.gson.JsonObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.MessageDigest
import java.util.UUID

data class SyncResult(
    val submitted: Int,
    val userId: String
)

class NeonBackendClient(
    private val context: Context
) {
    private val authApi: NeonAuthApi by lazy {
        Retrofit.Builder()
            .baseUrl(AUTH_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NeonAuthApi::class.java)
    }

    private val dataApi: NeonDataApi by lazy {
        Retrofit.Builder()
            .baseUrl(DATA_API_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NeonDataApi::class.java)
    }

    private val userId: String by lazy { getOrCreateLocalUserId() }

    suspend fun sync(metrics: List<NormalizedMetric>): SyncResult {
        if (metrics.isEmpty()) return SyncResult(0, userId)

        val tokenResponse = authApi.anonymousToken()
        val token = extractToken(tokenResponse)

        var submitted = 0

        metrics.chunked(CHUNK_SIZE).forEach { chunk ->
            val rows = chunk.map { metric ->
                NeonHealthRecord(
                    recordKey = recordKey(metric),
                    userId = userId,
                    metric = metric.metric,
                    startTime = metric.startTime,
                    endTime = metric.endTime,
                    valueDouble = metric.value,
                    unit = metric.unit,
                    sourcePackage = metric.sourcePackage,
                    raw = metric.metadata
                )
            }

            val response = dataApi.insertHealthRecords(
                authorization = "Bearer $token",
                rows = rows
            )

            if (!response.isSuccessful) {
                val errorText = response.errorBody()?.string()
                throw IllegalStateException(
                    "Neon Data API ${response.code()} ${response.message()} ${errorText ?: ""}".trim()
                )
            }

            submitted += rows.size
        }

        return SyncResult(submitted, userId)
    }

    private fun extractToken(json: JsonObject): String {
        val value = json.get("token")
        if (value != null && value.isJsonPrimitive && value.asString.isNotBlank()) {
            return value.asString
        }
        throw IllegalStateException(
            "Neon anonymous auth response did not contain token"
        )
    }

    private fun getOrCreateLocalUserId(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_USER_ID, null)
        if (!existing.isNullOrBlank()) return existing

        val created = "device-${UUID.randomUUID()}"
        prefs.edit().putString(KEY_USER_ID, created).apply()
        return created
    }

    private fun recordKey(metric: NormalizedMetric): String {
        val stable = buildString {
            append(metric.metric); append('|')
            append(metric.startTime); append('|')
            append(metric.endTime ?: ""); append('|')
            append(metric.value?.toString() ?: ""); append('|')
            append(metric.unit ?: ""); append('|')
            append(metric.sourcePackage ?: "")
        }

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(stable.toByteArray(Charsets.UTF_8))

        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val AUTH_BASE_URL =
            "https://ep-autumn-shadow-aesref0t.neonauth.c-2.us-east-2.aws.neon.tech/hcbridge/auth/"

        const val DATA_API_BASE_URL =
            "https://ep-autumn-shadow-aesref0t.apirest.c-2.us-east-2.aws.neon.tech/hcbridge/rest/v1/"

        const val CHUNK_SIZE = 500

        private const val PREFS_NAME = "hcbridge_backend"
        private const val KEY_USER_ID = "local_user_id"
    }
}
