package com.fourriders.next.scanner

import com.fourriders.next.data.TripOffer
import java.util.Locale

/** Conservative first-pass parser. Values are only populated when a recognizable label/unit exists. */
object OfferParser {
    private val money = Regex("(?i)(?:RM\\s*|MYR\\s*)(\\d{1,4}(?:[.,]\\d{1,2})?)|\\b(\\d{1,4}\\.\\d{2})\\s*(?:RM|MYR)\\b")
    private val distance = Regex("(?i)(\\d{1,3}(?:[.,]\\d{1,2})?)\\s*(?:km|kilomet(?:er|re)s?)\\b")
    private val duration = Regex("(?i)(\\d{1,3})\\s*(?:min|mins|minute|minutes)\\b")

    fun parse(text: String): TripOffer {
        val normalized = text.replace(',', '.')
        val fareMatch = money.find(normalized)
        val fareRaw = fareMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
        val kmRaw = distance.find(normalized)?.groupValues?.getOrNull(1)
        val minRaw = duration.find(normalized)?.groupValues?.getOrNull(1)
        val notes = mutableListOf<String>()
        if (fareRaw == null) notes += "Tambang belum dikenal pasti — semak sendiri."
        if (kmRaw == null) notes += "Jarak belum dikenal pasti — semak sendiri."
        if (minRaw == null) notes += "Masa perjalanan belum dikenal pasti — semak sendiri."
        return TripOffer(
            fare = fareRaw?.toDoubleOrNull(),
            distanceKm = kmRaw?.toDoubleOrNull(),
            durationMin = minRaw?.toIntOrNull(),
            pickup = extractLocation(text, listOf("pickup", "pick up", "ambil", "from")),
            dropoff = extractLocation(text, listOf("drop-off", "dropoff", "drop off", "destination", "to")),
            rawText = text,
            confidenceNotes = notes
        )
    }

    private fun extractLocation(text: String, labels: List<String>): String? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        for (i in lines.indices) {
            val line = lines[i]
            labels.firstOrNull { line.lowercase(Locale.ROOT).startsWith(it) } ?: continue
            val remainder = line.substringAfter(':', "").trim()
            if (remainder.length >= 3) return remainder
            if (i + 1 < lines.size && lines[i + 1].length >= 3) return lines[i + 1]
        }
        return null
    }
}
