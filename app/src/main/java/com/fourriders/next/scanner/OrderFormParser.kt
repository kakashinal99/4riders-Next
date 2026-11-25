package com.fourriders.next.scanner

import java.util.Locale

/** One stop (pickup or drop-off) of an order, split into separate categories. */
data class StopForm(
    val seen: Boolean = false,
    val name: String? = null,
    val location: String? = null,
    val unit: String? = null,
    val details: String? = null
)

data class OrderForm(
    val fare: Double? = null,
    val orderId: String? = null,
    val pickup: StopForm = StopForm(),
    val dropoff: StopForm = StopForm(),
    val notes: List<String> = emptyList()
) {
    val isEmpty: Boolean
        get() = fare == null && orderId == null && !pickup.seen && !dropoff.seen
}

/**
 * Turns the raw text read from the screen into a form (fare, pickup, drop-off) and
 * merges successive scans of the same order, so scrolling adds information instead
 * of replacing it. Pure Kotlin: no Android dependencies, so it can be unit-tested.
 */
object OrderFormParser {
    const val NEED_MORE = "need more info • scroll"

    private val money = Regex("(?i)(?<![A-Za-z])(?:RM|MYR)\\s*([0-9]{1,4}(?:[.,][0-9]{1,2})?)")
    private val moneyOnly = Regex("(?i)^(?:RM|MYR)\\s*[0-9]{1,4}(?:[.,][0-9]{1,2})?$")
    private val fareLabels = listOf("net earnings", "you earn", "earnings", "fare", "tambang", "pendapatan")

    private val stopHeading = Regex(
        "(?i)^(?:\\d+\\s*[.)]\\s*)?(pick\\s*-?\\s*up|drop\\s*-?\\s*off)" +
            "(?:\\s+(?:item|location|point|address|stop))?\\s*:?$"
    )
    private val nameLabel = Regex(
        "(?i)^(?:sender|recipient|receiver|customer|contact|merchant|shop|store|pengirim|penerima)" +
            "(?:'s)?\\s*name\\s*:?\\s*(.*)$"
    )
    private val detailsLabel = Regex("(?i)^address\\s*details?\\s*:?\\s*(.*)$")
    private val orderIdLabel = Regex("(?i)^order\\s*(?:id|no\\.?|number)\\s*:?\\s*(.*)$")
    private val orderIdPattern = Regex("\\b[A-Z]{1,4}-\\d{1,3}-[A-Z0-9]{8,}\\b")
    private val metricOnly = Regex("(?i)^[0-9]+(?:[.,][0-9]+)?\\s*(?:km|m|min|mins|minutes?|h|hrs?|hours?)$")
    private val knownLabel = Regex("(?i)^(?:notes?|items?|hide.*|show.*|.*details\\s*\\S?|phone.*|contact number.*)$")

    private val unitWithCode = Regex(
        "(?i)^(?:(?:unit|lot|no\\.?|blok|block|level|lvl|tingkat|aras|floor|suite|ste|apt|apartment)(?![A-Za-z])\\s*|#\\s*)" +
            "([A-Za-z]{0,3}[.\\-]?\\d[\\w.\\-/]*)(.*)$"
    )
    private val unitLevelWord = Regex("(?i)^(?:level|lvl|floor|tingkat|aras)\\s+[A-Za-z]+$")
    private val unitBlockLetter = Regex("(?i)^(?:blok|block|tower|menara)\\s+[A-Za-z0-9]{1,3}$")
    private val unitPureCode = Regex("(?i)^[A-Z]{0,2}-?\\d{1,3}[A-Z]?(?:-\\d{1,3}[A-Z]?){1,2}$")

    private class Section(val isPickup: Boolean) {
        val lines = mutableListOf<String>()
    }

    fun parse(raw: String): OrderForm {
        val lines = normalize(raw).lines().map { it.trim() }.filter { it.isNotBlank() }
        val notes = mutableListOf<String>()

        val sections = mutableListOf<Section>()
        for (line in lines) {
            val heading = stopHeading.find(line)
            if (heading != null) {
                sections += Section(heading.groupValues[1].lowercase(Locale.ROOT).startsWith("pick"))
            } else {
                sections.lastOrNull()?.lines?.add(line)
            }
        }

        fun stopFor(pickup: Boolean): StopForm =
            sections.filter { it.isPickup == pickup }
                .map { parseStop(it.lines) }
                .fold(StopForm()) { acc, next -> mergeStop(acc, next) }

        val pickup = stopFor(true)
        val dropoff = stopFor(false)
        if (pickup.seen && pickup.location == null) notes += "Pickup: tajuk dijumpai tapi alamat tidak dikenal pasti."
        if (dropoff.seen && dropoff.location == null) notes += "Drop-off: tajuk dijumpai tapi alamat tidak dikenal pasti."

        return OrderForm(
            fare = extractFare(lines, notes),
            orderId = extractOrderId(lines),
            pickup = pickup,
            dropoff = dropoff,
            notes = notes
        )
    }

    /** Combine a new scan into the running order. A different order starts a fresh form. */
    fun merge(previous: OrderForm?, latest: OrderForm): OrderForm {
        if (previous == null || isDifferentOrder(previous, latest)) return latest
        return OrderForm(
            fare = latest.fare ?: previous.fare,
            orderId = latest.orderId ?: previous.orderId,
            pickup = mergeStop(previous.pickup, latest.pickup),
            dropoff = mergeStop(previous.dropoff, latest.dropoff),
            notes = latest.notes
        )
    }

    fun render(form: OrderForm): List<String> {
        val out = mutableListOf<String>()
        out += "FARE: " + (form.fare?.let { "RM %.2f".format(Locale.US, it) } ?: "NOT FOUND")
        form.orderId?.let { out += "ORDER ID: $it" }
        out += "PICKUP"
        out += stopLines(form.pickup)
        out += "DROP-OFF"
        out += stopLines(form.dropoff)
        return out
    }

    private fun stopLines(stop: StopForm): List<String> {
        if (!stop.seen) {
            return listOf("  Name: $NEED_MORE", "  Location: $NEED_MORE", "  Unit: $NEED_MORE")
        }
        val out = mutableListOf(
            "  Name: ${stop.name ?: "NOT FOUND"}",
            "  Location: ${stop.location ?: "NOT FOUND"}",
            "  Unit: ${stop.unit ?: "-"}"
        )
        stop.details?.let { out += "  Details: $it" }
        return out
    }

    private fun isDifferentOrder(a: OrderForm, b: OrderForm): Boolean {
        if (a.orderId != null && b.orderId != null) return a.orderId != b.orderId
        if (a.fare != null && b.fare != null && a.fare != b.fare) return true
        if (a.pickup.location != null && b.pickup.location != null && a.pickup.location != b.pickup.location) return true
        if (a.dropoff.location != null && b.dropoff.location != null && a.dropoff.location != b.dropoff.location) return true
        return false
    }

    private fun mergeStop(old: StopForm, new: StopForm) = StopForm(
        seen = old.seen || new.seen,
        name = new.name ?: old.name,
        location = new.location ?: old.location,
        unit = new.unit ?: old.unit,
        details = new.details ?: old.details
    )

    private fun normalize(raw: String): String = raw
        .replace('\u00A0', ' ')
        .replace('\u2007', ' ')
        .replace('\u202F', ' ')
        .replace('\u2019', '\'')
        .replace('\u2018', '\'')

    private fun toDecimal(value: String): Double? =
        value.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 && it < 100_000.0 }

    private fun extractFare(lines: List<String>, notes: MutableList<String>): Double? {
        for (label in fareLabels) {
            for ((index, line) in lines.withIndex()) {
                if (!line.lowercase(Locale.ROOT).contains(label)) continue
                money.find(line)?.groupValues?.get(1)?.let(::toDecimal)?.let { return it }
                val next = lines.getOrNull(index + 1)
                if (next != null && moneyOnly.matches(next)) {
                    money.find(next)?.groupValues?.get(1)?.let(::toDecimal)?.let { return it }
                }
            }
        }
        val all = lines.flatMap { line ->
            money.findAll(line).mapNotNull { toDecimal(it.groupValues[1]) }.toList()
        }.distinct()
        if (all.size == 1) return all.first()
        if (all.size > 1) {
            notes += "Fare: banyak nilai RM (" +
                all.joinToString(", ") { "%.2f".format(Locale.US, it) } + ") tanpa label."
        }
        return null
    }

    private fun extractOrderId(lines: List<String>): String? {
        for (line in lines) orderIdPattern.find(line)?.let { return it.value }
        for ((index, line) in lines.withIndex()) {
            val match = orderIdLabel.find(line) ?: continue
            val sameLine = match.groupValues[1].trim()
            if (sameLine.isNotBlank()) return sameLine
            lines.getOrNull(index + 1)?.takeIf { !isLabel(it) }?.let { return it }
        }
        return null
    }

    private fun isLabel(line: String): Boolean =
        nameLabel.matches(line) || detailsLabel.matches(line) || orderIdLabel.matches(line) ||
            knownLabel.matches(line) || line.endsWith(":")

    /** Value after a label: same line if present, otherwise the next non-label line. */
    private fun valueAfter(lines: List<String>, index: Int, sameLine: String): Pair<String?, Int> {
        if (sameLine.isNotBlank()) return sameLine.trim() to index + 1
        val next = lines.getOrNull(index + 1)
        if (next != null && !isLabel(next)) return next to index + 2
        return null to index + 1
    }

    private fun parseStop(lines: List<String>): StopForm {
        var name: String? = null
        var details: String? = null
        var address: String? = null
        var searchingAddress = true
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val nameMatch = nameLabel.find(line)
            val detailsMatch = detailsLabel.find(line)
            when {
                nameMatch != null -> {
                    searchingAddress = false
                    val (value, next) = valueAfter(lines, index, nameMatch.groupValues[1])
                    if (name == null) name = value
                    index = next
                }
                detailsMatch != null -> {
                    searchingAddress = false
                    val (value, next) = valueAfter(lines, index, detailsMatch.groupValues[1])
                    val cleaned = value?.replace(Regex("^[\\-\\u2013\\u2014.\\s]+"), "")?.trim()
                    if (details == null && !cleaned.isNullOrBlank()) details = cleaned
                    index = next
                }
                isLabel(line) -> {
                    searchingAddress = false
                    index++
                }
                else -> {
                    if (searchingAddress && address == null && looksLikeAddress(line)) {
                        address = line
                        searchingAddress = false
                    }
                    index++
                }
            }
        }
        val (location, unit) = address?.let(::splitAddress) ?: (null to null)
        return StopForm(seen = true, name = name, location = location, unit = unit, details = details)
    }

    private fun looksLikeAddress(line: String): Boolean =
        line.length >= 8 &&
            line.any { it.isLetter() } &&
            !moneyOnly.matches(line) &&
            !metricOnly.matches(line) &&
            !orderIdPattern.matches(line)

    /** Splits an address into (location, unit) where unit holds lot/unit/level/block parts. */
    private fun splitAddress(address: String): Pair<String?, String?> {
        val units = mutableListOf<String>()
        val rest = mutableListOf<String>()
        for (segment in address.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val withCode = unitWithCode.find(segment)
            when {
                withCode != null -> {
                    val leftover = withCode.groupValues[2]
                    units += segment.removeSuffix(leftover).trim()
                    if (leftover.isNotBlank()) rest += leftover.trim()
                }
                unitLevelWord.matches(segment) || unitBlockLetter.matches(segment) || unitPureCode.matches(segment) ->
                    units += segment
                else -> rest += segment
            }
        }
        val location = rest.map { it.trimEnd('.', ' ') }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .joinToString(", ")
        return (location.ifBlank { null }) to (units.joinToString(" • ").ifBlank { null })
    }
}
