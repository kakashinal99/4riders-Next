package com.fourriders.next.data

data class TripOffer(
    val fare: Double? = null,
    val distanceKm: Double? = null,
    val durationMin: Int? = null,
    val pickup: String? = null,
    val dropoff: String? = null,
    val rawText: String = "",
    val confidenceNotes: List<String> = emptyList()
) {
    val perKm: Double? get() = if (fare != null && distanceKm != null && distanceKm > 0) fare / distanceKm else null
    val perHour: Double? get() = if (fare != null && durationMin != null && durationMin > 0) fare / (durationMin / 60.0) else null
}
