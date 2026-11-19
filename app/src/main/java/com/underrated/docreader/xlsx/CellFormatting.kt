package com.underrated.docreader.xlsx

import java.text.DecimalFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Enough of Excel's number-format language to render ordinary business spreadsheets:
 * General, integers and decimals with grouping, percentages, currency-ish prefixes,
 * and the common date/time codes.
 *
 * Deliberately not a full implementation — Excel's format grammar includes
 * conditional sections, colour codes, fractions and locale tokens this does not
 * attempt. Anything unrecognised falls back to a plain numeric rendering rather
 * than guessing.
 */
object CellFormatting {

    /** Built-in numFmtId values; ids not listed here come from <numFmts>. */
    private val BUILTIN = mapOf(
        0 to "General", 1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00",
        9 to "0%", 10 to "0.00%", 11 to "0.00E+00", 12 to "# ?/?", 13 to "# ??/??",
        14 to "mm-dd-yy", 15 to "d-mmm-yy", 16 to "d-mmm", 17 to "mmm-yy",
        18 to "h:mm AM/PM", 19 to "h:mm:ss AM/PM", 20 to "h:mm", 21 to "h:mm:ss",
        22 to "m/d/yy h:mm",
        37 to "#,##0 ;(#,##0)", 38 to "#,##0 ;[Red](#,##0)",
        39 to "#,##0.00;(#,##0.00)", 40 to "#,##0.00;[Red](#,##0.00)",
        45 to "mm:ss", 46 to "[h]:mm:ss", 47 to "mmss.0", 48 to "##0.0E+0", 49 to "@",
    )

    fun builtinFormat(id: Int): String? = BUILTIN[id]

    /** A format code is a date format if it carries date/time tokens outside quotes. */
    fun isDateFormat(code: String): Boolean {
        if (code.isEmpty() || code == "General" || code == "@") return false
        var inQuotes = false
        var inBracket = false
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '"' -> inQuotes = !inQuotes
                c == '[' -> inBracket = true
                c == ']' -> inBracket = false
                inQuotes || inBracket -> Unit
                c == 'y' || c == 'd' || c == 'h' || c == 's' -> return true
                c == 'm' -> return true // ambiguous (month/minute) but still a date format
            }
            i++
        }
        return false
    }

    /**
     * Excel serial date -> epoch millis (UTC), using the 1900 date system including
     * its deliberate 1900-02-29 bug, which is why serials above 59 shift by one day.
     */
    fun serialToMillis(serial: Double): Long {
        val adjusted = if (serial >= 60.0) serial - 1.0 else serial
        val days = Math.floor(adjusted).toLong()
        val fraction = adjusted - days
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
        cal.clear()
        cal.set(1899, Calendar.DECEMBER, 31, 0, 0, 0)
        cal.add(Calendar.DAY_OF_MONTH, days.toInt())
        val millisInDay = Math.round(fraction * 24 * 60 * 60 * 1000)
        return cal.timeInMillis + millisInDay
    }

    fun formatDate(serial: Double, code: String): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
        cal.timeInMillis = serialToMillis(serial)
        val y = cal.get(Calendar.YEAR)
        val mo = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val h24 = cal.get(Calendar.HOUR_OF_DAY)
        val mi = cal.get(Calendar.MINUTE)
        val se = cal.get(Calendar.SECOND)

        val lower = code.lowercase(Locale.US)
        val hasDate = lower.contains('y') || lower.contains('d') ||
            Regex("m{1,5}(?![^\\[]*])").containsMatchIn(lower) && !lower.contains("h")
        val hasTime = lower.contains('h') || lower.contains("ss") || lower.contains("am/pm")

        val datePart = when {
            lower.contains("yyyy") && lower.contains("mmm") -> "%02d %s %04d".format(d, monthName(mo), y)
            lower.contains("mmm") && lower.contains("yy") -> "%s-%02d".format(monthName(mo), y % 100)
            lower.contains("mmm") -> "%d %s".format(d, monthName(mo))
            lower.contains("yyyy") -> "%04d-%02d-%02d".format(y, mo, d)
            lower.contains("yy") -> "%02d/%02d/%02d".format(mo, d, y % 100)
            else -> "%04d-%02d-%02d".format(y, mo, d)
        }
        val timePart = when {
            lower.contains("am/pm") -> {
                val h12 = if (h24 % 12 == 0) 12 else h24 % 12
                val suffix = if (h24 < 12) "AM" else "PM"
                if (lower.contains("ss")) "%d:%02d:%02d %s".format(h12, mi, se, suffix)
                else "%d:%02d %s".format(h12, mi, suffix)
            }
            lower.contains("ss") -> "%02d:%02d:%02d".format(h24, mi, se)
            else -> "%02d:%02d".format(h24, mi)
        }

        return when {
            hasDate && hasTime -> "$datePart $timePart"
            hasTime -> timePart
            else -> datePart
        }
    }

    private fun monthName(m: Int) = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    ).getOrElse(m - 1) { "?" }

    /** Formats a number against [code], falling back to a tidy general rendering. */
    fun formatNumber(value: Double, code: String): String {
        if (code.isEmpty() || code.equals("General", ignoreCase = true)) return general(value)
        if (code == "@") return general(value)

        // Use only the positive section; negatives get a leading minus.
        val section = code.split(';').firstOrNull() ?: code
        val cleaned = section
            .replace(Regex("\\[[^]]*]"), "")       // [Red], [h], locale ids
            .replace("_", "")                       // padding hints
            .replace("*", "")
            .trim()

        val isPercent = cleaned.contains('%')
        val scaled = if (isPercent) value * 100.0 else value

        val prefix = Regex("^[\"']?([^#0?]*)[\"']?").find(cleaned)?.groupValues?.get(1)
            ?.replace("\"", "")?.takeWhile { it !in "#0?" } ?: ""

        val numericPart = cleaned.filter { it in "#0.,?" }
        if (numericPart.isEmpty()) return general(value)

        val grouping = numericPart.contains(',')
        val decimals = numericPart.substringAfter('.', "").count { it == '0' || it == '#' }

        val pattern = buildString {
            if (grouping) append("#,##0") else append("0")
            if (decimals > 0) {
                append('.')
                repeat(decimals) { append('0') }
            }
        }
        return try {
            val df = DecimalFormat(pattern)
            prefix + df.format(scaled) + if (isPercent) "%" else ""
        } catch (t: Throwable) {
            general(value)
        }
    }

    /** Excel's General: integers plain, otherwise trim trailing zeros. */
    fun general(value: Double): String {
        if (value == Math.floor(value) && !value.isInfinite() && Math.abs(value) < 1e15) {
            return value.toLong().toString()
        }
        val s = DecimalFormat("0.##########").format(value)
        return s
    }
}

/** Column index (0-based) to Excel letters: 0 -> A, 26 -> AA. */
fun columnLabel(index: Int): String {
    var n = index
    val sb = StringBuilder()
    while (n >= 0) {
        sb.append(('A' + (n % 26)))
        n = n / 26 - 1
    }
    return sb.reverse().toString()
}

/** Parses "BC12" into zero-based (row, column). Returns null if malformed. */
fun parseCellRef(ref: String): Pair<Int, Int>? {
    var i = 0
    var col = 0
    while (i < ref.length && ref[i].isLetter()) {
        col = col * 26 + (ref[i].uppercaseChar() - 'A' + 1)
        i++
    }
    if (i == 0 || i >= ref.length) return null
    val row = ref.substring(i).toIntOrNull() ?: return null
    if (row !in 1..1_048_576 || col !in 1..16_384) return null
    return (row - 1) to (col - 1)
}
