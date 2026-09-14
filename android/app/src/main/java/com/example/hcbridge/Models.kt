package com.example.hcbridge

data class NormalizedMetric(
    val metric: String,
    val startTime: String,
    val endTime: String? = null,
    val value: Double? = null,
    val unit: String? = null,
    val sourcePackage: String? = null,
    val metadata: Map<String, Any?> = emptyMap()
)

data class IngestRequest(
    val userId: String,
    val metrics: List<NormalizedMetric>
)
