package com.fourriders.next.scanner

import com.fourriders.next.data.TripOffer
import java.util.Locale

/**
 * First-pass, platform-agnostic parser. It intentionally refuses to guess when
 * more than one plausible fare, distance or duration is present.
 */
object OfferParser {
    private enum class StopKind { PICKUP, DROPOFF }

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

    // Lines that say "this amount is what the driver earns", e.g. "You'll earn RM 14.10"
    // or "Current net earnings: RM5.20". Preferred over any other RM value on screen.
    private val fareLabelPattern = Regex(
        "(?i)(you['’]?ll earn|you will earn|\\bnet earnings?\\b|\\bearnings?\\b|\\bpendapatan\\b|\\btambang\\b|\\bfare\\b)"
    )
    private val moneyStartPattern = Regex("(?i)^(?:RM|MYR)\\s*\\d")

    // Numbered stop headings: "1. Collect from", "2. Deliver to" (GrabFood) and
    // "1. Pick Up Item", "2. Drop Off Item" (GrabExpress).
    private val stopHeadingPattern = Regex(
        "(?i)^\\d+\\.\\s*(collect\\s*from|pick\\s*up(?:\\s+item)?|deliver\\s*to|drop\\s*off(?:\\s+item)?)$"
    )
    private val stopCountPattern = Regex("(?i)(\\d+)\\s*/\\s*(\\d+)\\s*stops?\\s*to\\s*go")
    private val ratingPattern = Regex("^(?:.{0,30}?[\\s·•.])?[1-5]\\.\\d$")
    private val grabIdPattern = Regex("\\b(?:GF|GM|GK)-[0-9A-Z]{2,}|\\bIN-\\d-[A-Z0-9]{6,}")

    // Rows that start a new section, so an address block must stop before them.
    private val exactBlockEnds = setOf(
        "arrive by", "order id", "service", "payment method", "special instruction",
        "item details", "address details", "notes", "booking id", "no of items",
        "show details", "hide details", "back to top", "cancel", "call", "promo",
        "grabpay", "route details"
    )
    private val prefixBlockEnds = listOf(
        "sender's name", "sender’s name", "recipient's name", "recipient’s name",
        "arrive by", "order id", "booking id", "payment method", "special instruction",
        "call ", "chat with", "cancel ", "show details", "hide details", "back to top",
        "drop-off details", "pickup details", "pick up details", "no of items",
        "address details", "item details", "grab"
    )

    fun parse(text: String): TripOffer {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val fareCandidates = moneyIn(text).distinct()
        val distanceCandidates = distancePattern.findAll(text).mapNotNull {
            it.groupValues.getOrNull(1)?.toDecimal()
        }.distinct().toList()
        val durationCandidates = findDurations(text).distinct()

        val labeledFare = findLabeledFare(lines)
        val fare = labeledFare ?: fareCandidates.singleOrNull()
        val distance = distanceCandidates.singleOrNull()
        val duration = durationCandidates.singleOrNull()
        val pickup = extractLocation(lines, StopKind.PICKUP, listOf("pickup", "pick up", "pick-up", "from", "origin", "starting point", "ambil"))
        val dropoff = extractLocation(lines, StopKind.DROPOFF, listOf("dropoff", "drop off", "drop-off", "destination", "to", "going to", "hantar"))

        val notes = mutableListOf<String>()
        if (labeledFare != null) {
            if (fareCandidates.size > 1) notes += "Tambang diambil dari baris 'earn'; nilai RM lain (bonus/tip?) diabaikan."
        } else if (fareCandidates.size > 1) {
            notes += "Beberapa nilai wang dikesan; tambang tidak dipilih secara automatik."
        } else if (fare == null) {
            notes += "Tambang RM/MYR belum dikenal pasti."
        }
        if (distanceCandidates.size > 1) notes += "Beberapa jarak dikesan; semak jarak perjalanan yang betul."
        else if (distance == null) notes += "Jarak dalam KM belum dikenal pasti."
        if (durationCandidates.size > 1) notes += "Beberapa tempoh dikesan; semak masa perjalanan yang betul."
        else if (duration == null) notes += "Tempoh perjalanan belum dikenal pasti."
        if (pickup == null) notes += "Lokasi pickup belum dikenal pasti."
        if (dropoff == null) notes += "Lokasi drop-off belum dikenal pasti."
        stopCountPattern.find(text)?.groupValues?.getOrNull(2)?.toIntOrNull()?.let { total ->
            if (total > 2) notes += "Order berbilang stop ($total stop); tambang untuk semua stop."
        }

        return TripOffer(
            fare = fare,
            distanceKm = distance,
            durationMin = duration,
            pickup = pickup,
            dropoff = dropoff,
            platform = detectPlatform(text),
            rawText = text,
            confidenceNotes = notes
        )
    }

    private fun detectPlatform(text: String): String {
        val value = text.lowercase(Locale.ROOT)
        return when {
            "grab" in value || grabIdPattern.containsMatchIn(text) || stopCountPattern.containsMatchIn(text) -> "Grab"
            "indrive" in value || "in drive" in value -> "inDrive"
            "foodpanda" in value -> "foodpanda"
            "lalamove" in value -> "Lalamove"
            "maxim" in value -> "Maxim"
            "airasia ride" in value || "airasia" in value -> "AirAsia Ride"
            else -> "Unidentified app"
        }
    }

    private fun moneyIn(text: String): List<Double> = moneyPatterns.flatMap { pattern ->
        pattern.findAll(text).mapNotNull { it.groupValues.getOrNull(1)?.toDecimal() }.toList()
    }

    private fun findLabeledFare(lines: List<String>): Double? {
        for (index in lines.indices) {
            if (!fareLabelPattern.containsMatchIn(lines[index])) continue
            moneyIn(lines[index]).firstOrNull()?.let { return it }
            val next = lines.getOrNull(index + 1)
            if (next != null && moneyStartPattern.containsMatchIn(next)) {
                moneyIn(next).firstOrNull()?.let { return it }
            }
        }
        return null
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

    private fun headingKind(line: String): StopKind? {
        val keyword = stopHeadingPattern.find(line.trim())?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT)
            ?: return null
        return if (keyword.startsWith("collect") || keyword.startsWith("pick")) StopKind.PICKUP else StopKind.DROPOFF
    }

    private fun isLinkText(normalized: String): Boolean =
        normalized.startsWith("drop-off details") ||
            normalized.startsWith("pickup details") ||
            normalized.startsWith("pick up details") ||
            normalized.startsWith("show details") ||
            normalized.startsWith("hide details")

    private fun isRatingLine(value: String): Boolean {
        val v = value.trim()
        return v == "·" || v == "•" || ratingPattern.matches(v)
    }

    /** True when lines[index] no longer belongs to the address block above it. */
    private fun isBlockEnd(lines: List<String>, index: Int): Boolean {
        val line = lines[index]
        val normalized = line.lowercase(Locale.ROOT).trim()
        if (headingKind(line) != null) return true
        if (stopCountPattern.containsMatchIn(normalized)) return true
        if (normalized in exactBlockEnds || prefixBlockEnds.any { normalized.startsWith(it) }) return true
        if (isRatingLine(normalized)) return true
        // A short line right before a rating is the customer/merchant name, not address.
        val next = lines.getOrNull(index + 1)
        if (next != null && normalized.length <= 30 && isRatingLine(next)) return true
        return false
    }

    /** Joins the wrapped address lines that follow a numbered stop heading. */
    private fun extractStop(lines: List<String>, kind: StopKind): String? {
        for (index in lines.indices) {
            if (headingKind(lines[index]) != kind) continue
            val block = mutableListOf<String>()
            var cursor = index + 1
            while (cursor < lines.size && block.size < 8 && !isBlockEnd(lines, cursor)) {
                block += lines[cursor]
                cursor++
            }
            val joined = block.joinToString(" ").replace(Regex("\\s+"), " ").trim()
            if (joined.length >= 3) return joined
        }
        return null
    }

    private fun extractLocation(lines: List<String>, kind: StopKind, labels: List<String>): String? {
        extractStop(lines, kind)?.let { return it }

        for (index in lines.indices) {
            val line = lines[index]
            val normalized = line.lowercase(Locale.ROOT).trim()
            if (headingKind(line) != null || isLinkText(normalized)) continue
            val label = labels.firstOrNull {
                Regex("^" + Regex.escape(it) + "(?![a-z])").containsMatchIn(normalized)
            } ?: continue
            val remainder = line.substringAfter(':', "")
                .ifBlank { line.drop(label.length).trim().trim('-', '—', ':') }
                .trim()
                .takeUnless { it.equals("item", ignoreCase = true) }
                .orEmpty()
            if (remainder.length >= 3 && !looksLikeMetric(remainder)) return remainder
            val next = lines.drop(index + 1)
                .takeWhile { headingKind(it) == null }
                .firstOrNull { candidate ->
                    val value = candidate.trim()
                    value.length >= 8 &&
                        !looksLikeMetric(value) &&
                        !isLinkText(value.lowercase(Locale.ROOT)) &&
                        !value.equals("pickup", ignoreCase = true) &&
                        !value.equals("pick up", ignoreCase = true) &&
                        !value.equals("dropoff", ignoreCase = true) &&
                        !value.equals("drop off", ignoreCase = true) &&
                        !value.startsWith("Order ID", ignoreCase = true)
                }
            if (next != null) return next
        }
        return null
    }

    private fun looksLikeMetric(value: String): Boolean =
        moneyPatterns.any { it.containsMatchIn(value) } ||
            distancePattern.containsMatchIn(value) ||
            compactTimePattern.containsMatchIn(value) ||
            wordTimePattern.containsMatchIn(value)

    private fun String.toDecimal(): Double? =
        replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 && it < 100_000.0 }
}
