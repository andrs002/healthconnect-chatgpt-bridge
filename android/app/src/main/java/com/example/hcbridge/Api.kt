package com.example.hcbridge

import com.google.gson.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface NeonAuthApi {
    @GET("token/anonymous")
    suspend fun anonymousToken(): JsonObject
}

interface NeonDataApi {
    @POST("health_records")
    suspend fun insertHealthRecords(
        @Header("Authorization") authorization: String,
        @Header("Prefer") prefer: String = "resolution=ignore-duplicates,return=minimal",
        @Query("on_conflict") onConflict: String = "user_id,record_key",
        @Body rows: List<NeonHealthRecord>
    ): Response<Unit>
}
