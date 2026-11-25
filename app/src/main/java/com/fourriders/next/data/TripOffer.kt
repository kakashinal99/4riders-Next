package com.fourriders.next.data

data class TripOffer(
    val fare: Double? = null,
    val distanceKm: Double? = null,
    val durationMin: Int? = null,
    val pickup: String? = null,
    val dropoff: String? = null,
    val platform: String = "Unknown app",
    val rawText: String = "",
    val confidenceNotes: List<String> = emptyList()
) {
    val perKm: Double? get() =
        if (fare != null && distanceKm != null && distanceKm > 0) fare / distanceKm else null

    val perHour: Double? get() =
        if (fare != null && durationMin != null && durationMin > 0) fare / (durationMin / 60.0) else null

    // Completeness, not a promise that OCR values are correct.
    val dataCompleteness: Int get() =
        listOf(fare, distanceKm, durationMin, pickup, dropoff).count { it != null } * 20

    val isReadyForReview: Boolean get() =
        fare != null && distanceKm != null && durationMin != null
}
