package com.example.hcbridge

import com.google.gson.annotations.SerializedName

data class NormalizedMetric(
    val metric: String,
    val startTime: String,
    val endTime: String? = null,
    val value: Double? = null,
    val unit: String? = null,
    val sourcePackage: String? = null,
    val metadata: Map<String, Any?> = emptyMap()
)

data class NeonHealthRecord(
    @SerializedName("record_key") val recordKey: String,
    @SerializedName("user_id") val userId: String,
    @SerializedName("metric") val metric: String,
    @SerializedName("start_time") val startTime: String,
    @SerializedName("end_time") val endTime: String? = null,
    @SerializedName("value_double") val valueDouble: Double? = null,
    @SerializedName("unit") val unit: String? = null,
    @SerializedName("source_package") val sourcePackage: String? = null,
    @SerializedName("raw") val raw: Map<String, Any?> = emptyMap()
)
