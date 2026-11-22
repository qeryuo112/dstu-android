package com.deepstudent.features.media.api

data class MediaDeviceTotal(
    val deviceId: String,
    val seconds: Long,
    val events: Int,
)

data class MediaSubtitle(
    val planId: String,
    val version: Long,
    val index: Long,
    val text: String,
    val startMillis: Long,
    val endMillis: Long,
    val eventId: String,
)

data class MediaPlanVersion(
    val planId: String,
    val version: Long,
)

data class MediaResume(
    val positionMillis: Long,
    val durationMillis: Long,
    val eventId: String,
    val observedAt: String,
)

data class MediaSummary(
    val resourceId: String,
    val logicalDate: String,
    val seconds: Long,
    val resume: MediaResume?,
    val byDevice: List<MediaDeviceTotal>,
    val subtitles: List<MediaSubtitle>,
    val latestPlanVersions: List<MediaPlanVersion>,
    val eventCount: Int,
) {
    val position: Long? get() = resume?.positionMillis
    val positionMillis: Long? get() = resume?.positionMillis
    val durationMillis: Long? get() = resume?.durationMillis
    val resumeEventId: String? get() = resume?.eventId
    val resumeObservedAt: String? get() = resume?.observedAt
}
