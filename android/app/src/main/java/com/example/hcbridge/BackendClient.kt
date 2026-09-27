package com.example.hcbridge

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

data class SyncResult(
    val submitted: Int,
    val userId: String
)

class NeonBackendClient(
    private val context: Context
) {
    // PostgREST bulk inserts require every object in the JSON array to expose
    // the same columns. Keep nullable fields as explicit JSON null values.
    private val gson = GsonBuilder()
        .serializeNulls()
        .create()

    private val authApi: NeonAuthApi by lazy {
        Retrofit.Builder()
            .baseUrl(AUTH_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(NeonAuthApi::class.java)
    }

    private val dataApi: NeonDataApi by lazy {
        Retrofit.Builder()
            .baseUrl(DATA_API_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(NeonDataApi::class.java)
    }

    private val userId: String by lazy { getOrCreateLocalUserId() }

    suspend fun sync(metrics: List<NormalizedMetric>): SyncResult {
        if (metrics.isEmpty()) return SyncResult(0, userId)

        val tokenResponse = retryTransientCall {
            authApi.anonymousToken()
        }
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

            val response = retryTransientResponse {
                dataApi.insertHealthRecords(
                    authorization = "Bearer $token",
                    rows = rows
                )
            }

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

    private suspend fun <T> retryTransientCall(block: suspend () -> T): T {
        var retryDelayMs = INITIAL_RETRY_DELAY_MS

        repeat(MAX_REQUEST_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (t: Throwable) {
                if (!isTransientFailure(t) || attempt == MAX_REQUEST_ATTEMPTS - 1) {
                    throw t
                }
            }

            delay(retryDelayMs)
            retryDelayMs *= 2
        }

        error("Retry loop completed unexpectedly")
    }

    private suspend fun retryTransientResponse(
        block: suspend () -> Response<Unit>
    ): Response<Unit> {
        var retryDelayMs = INITIAL_RETRY_DELAY_MS

        repeat(MAX_REQUEST_ATTEMPTS) { attempt ->
            try {
                val response = block()
                val shouldRetry = isTransientHttpStatus(response.code())

                if (!shouldRetry || attempt == MAX_REQUEST_ATTEMPTS - 1) {
                    return response
                }

                response.errorBody()?.close()
            } catch (t: Throwable) {
                if (!isTransientFailure(t) || attempt == MAX_REQUEST_ATTEMPTS - 1) {
                    throw t
                }
            }

            delay(retryDelayMs)
            retryDelayMs *= 2
        }

        error("Retry loop completed unexpectedly")
    }

    private fun isTransientFailure(t: Throwable): Boolean =
        t is IOException ||
            (t is HttpException && isTransientHttpStatus(t.code()))

    private fun isTransientHttpStatus(code: Int): Boolean =
        code == 408 || code == 425 || code == 429 || code in 500..599

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

        private const val MAX_REQUEST_ATTEMPTS = 3
        private const val INITIAL_RETRY_DELAY_MS = 1_000L

        private const val PREFS_NAME = "hcbridge_backend"
        private const val KEY_USER_ID = "local_user_id"
    }
}
