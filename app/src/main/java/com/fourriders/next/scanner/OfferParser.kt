package com.fourriders.next.scanner

import com.fourriders.next.data.TripOffer
import java.util.Locale

/**
 * Platform-neutral first-pass offer parser.
 *
 * Deliberately conservative: money is only accepted with an explicit currency marker,
 * and distance/time must carry recognizable units. OCR output is never treated as ground truth.
 */
object OfferParser {
    private val moneyPatterns = listOf(
        Regex("(?i)(?:RM|MYR)\\s*([0-9]{1,4}(?:[.,][0-9]{1,2})?)"),
        Regex("(?i)\\b([0-9]{1,4}(?:[.,][0-9]{1,2})?)\\s*(?:RM|MYR)\\b")
    )
    private val distancePattern = Regex(
        "(?i)\\b([0-9]{1,3}(?:[.,][0-9]{1,2})?)\\s*(km|kilometers?|kilometres?)\\b"
    )
    private val compactTimePattern = Regex(
        "(?i)\\b(?:(\\d{1,2})\\s*h(?:ours?)?\\s*)?(?:(\\d{1,3})\\s*m(?:in(?:utes?)?)?)\\b"
    )
    private val wordTimePattern = Regex(
        "(?i)\\b(?:(\\d{1,2})\\s*hours?)?\\s*(?:(\\d{1,3})\\s*minutes?)\\b"
    )
    private val hourOnlyPattern = Regex("(?i)\\b(\\d{1,2})\\s*(?:hours?|hrs?)\\b")

    fun parse(text: String): TripOffer {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val fareCandidates = moneyPatterns.mapNotNull { it.find(text)?.groupValues?.getOrNull(1)?.toDecimal() }
            .distinct()
        val distanceCandidates = distancePattern.findAll(text).mapNotNull {
            it.groupValues.getOrNull(1)?.toDecimal()
        }.distinct().toList()
        val durationCandidates = findDurations(text).distinct()

        val fare = fareCandidates.singleOrNull()
        val distance = distanceCandidates.singleOrNull()
        val duration = durationCandidates.singleOrNull()

        val pickup = extractLocation(lines, listOf(
            "pickup", "pick up", "pick-up", "from", "origin", "starting point", "ambil"
        ))
        val dropoff = extractLocation(lines, listOf(
            "dropoff", "drop off", "drop-off", "destination", "to", "going to", "hantar"
        ))

        val notes = mutableListOf<String>()
        if (fareCandidates.size > 1) notes += "Lebih daripada satu nilai wang dikesan; tambang tidak dipilih secara automatik."
        else if (fare == null) notes += "Tambang dengan label mata wang yang jelas belum dikenal pasti."
        if (distanceCandidates.size > 1) notes += "Lebih daripada satu jarak dikesan; semak sama ada jarak perjalanan atau jarak pickup."
        else if (distance == null) notes += "Jarak dalam KM belum dikenal pasti."
        if (durationCandidates.size > 1) notes += "Lebih daripada satu tempoh dikesan; semak masa perjalanan yang betul."
        else if (duration == null) notes += "Tempoh perjalanan belum dikenal pasti."
        if (pickup == null) notes += "Lokasi pickup belum dikenal pasti."
        if (dropoff == null) notes += "Lokasi drop-off belum dikenal pasti."

        return TripOffer(
            fare = fare,
            distanceKm = distance,
            durationMin = duration,
            pickup = pickup,
            dropoff = dropoff,
            rawText = text,
            confidenceNotes = notes
        )
    }

    private fun findDurations(text: String): List<Int> {
        val results = mutableListOf<Int>()
        compactTimePattern.findAll(text).forEach { match ->
            val hours = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
            val minutes = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            val total = hours * 60 + minutes
            if (total in 1..1440) results += total
        }
        wordTimePattern.findAll(text).forEach { match ->
            val hours = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
            val minutes = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            val total = hours * 60 + minutes
            if (total in 1..1440) results += total
        }
        hourOnlyPattern.findAll(text).forEach { match ->
            match.groupValues.getOrNull(1)?.toIntOrNull()?.let { hours ->
                if (hours in 1..24) results += hours * 60
            }
        }
        return results
    }

    private fun extractLocation(lines: List<String>, labels: List<String>): String? {
        for (index in lines.indices) {
            val line = lines[index]
            val normalized = line.lowercase(Locale.ROOT)
            val label = labels.firstOrNull { normalized.startsWith(it) } ?: continue
            val remainder = line.substringAfter(':', "").trim()
                .ifBlank { line.removePrefix(line.take(label.length)).trim().trim('-', '—', ':') }
            if (remainder.length >= 3 && !looksLikeMetric(remainder)) return remainder
            val next = lines.getOrNull(index + 1)
            if (next != null && next.length >= 3 && !looksLikeMetric(next)) return next
        }
        return null
    }

    private fun looksLikeMetric(value: String): Boolean =
        moneyPatterns.any { it.containsMatchIn(value) } ||
            distancePattern.containsMatchIn(value) ||
            compactTimePattern.containsMatchIn(value)

    private fun String.toDecimal(): Double? =
        replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 && it < 100_000.0 }
}
