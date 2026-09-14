package com.example.hcbridge

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface BridgeApi {
    @POST("v1/ingest")
    suspend fun ingest(
        @Header("Authorization") authorization: String,
        @Body body: IngestRequest
    )
}
