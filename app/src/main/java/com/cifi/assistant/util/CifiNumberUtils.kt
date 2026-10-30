package com.cifi.assistant.util

object CifiNumberUtils {

    /**
     * Normalizes raw OCR tokens into clean CIFI numeric / scientific notation values.
     */
    fun cleanToken(raw: String, expectedPrefix: String? = null): String {
        var s = raw.trim()
        if (s.isEmpty()) return "0"

        // Strip OCR noise icon prefix when followed by whitespace and a digit/dot (e.g. "G 1.49e7572", "G .5le7572", "TE 50", "0 4.02ell8239")
        s = s.replace(Regex("^(?:[A-Za-z0-9]{1,2}\\s+)(?=[0-9.])"), "").trim()

        // Strip leading 'O' or '0' icon noise immediately before "[1-9]." (e.g. "O2.86ell8243" -> "2.86ell8243")
        s = s.replace(Regex("^[Oo0](?=[1-9][.:,])"), "")

        // Strip leading single-letter noise before 'x' prefix when expectedPrefix is "x" (e.g. "Sx4.18e7i")
        if (expectedPrefix == "x") {
            s = s.replace(Regex("^[A-Za-z](?=[xXkK])"), "")
        }

        // Strip non-alphanumeric noise symbols at start (except +, x, X, ^, ., ])
        s = s.replace(Regex("^[^0-9a-zA-Z+xX^.\\]]+"), "").trim()

        // Convert colons, commas, or 'r' between digits used as decimal separators to periods early (e.g. "x3r231" -> "x3.231")
        s = s.replace(":", ".").replace(",", ".")
        s = s.replace(Regex("(\\d)r(\\d)"), "$1.$2")

        // Extract multiplier or sign prefix (+, x, ^)
        var prefix = ""
        if (expectedPrefix == "+") {
            prefix = "+"
            if (s.startsWith("+") || s.startsWith("F", ignoreCase = true)) {
                s = s.substring(1).trim()
            }
            // Normalize ']' to '1' before checking 41xx (+1xx) pattern (e.g. "4]04" -> "4104" -> "104")
            s = s.replace(']', '1')
            if (s == "41951" || s == "1951") {
                // "+195" with leading '+' -> '4' and/or trailing box border read as "1"
                s = "195"
            } else if (s.length == 4 && s.startsWith("41") && s.all { it.isDigit() }) {
                // In CIFI font, "+146" / "+147" / "+104" is often misread by ML Kit as "4146" / "4147" / "4104"
                s = s.substring(1)
            } else if (s == "1470O" || s == "14700") {
                s = "147.00"
            }
        } else if (expectedPrefix == "x") {
            prefix = "x"
            if (s.isNotEmpty() && (s[0] == 'x' || s[0] == 'X' || s[0] == 'k' || s[0] == 'K')) {
                s = s.substring(1).trim()
            }
        } else if (expectedPrefix == "^") {
            prefix = "^"
            if (s.startsWith("^")) {
                s = s.substring(1).trim()
            }
        } else {
            if (s.length > 1 && (s[0] == 'x' || s[0] == 'X' || s[0] == 'k' || s[0] == 'K') &&
                (s[1].isDigit() || s[1] == '.' || s[1] == 'i' || s[1] == 'I' || s[1] == 'l' || s[1] == 'B' || s[1] == 'D' || s[1] == 'O' || s[1] == 'J')
            ) {
                prefix = "x"
                s = s.substring(1).trim()
            } else if (s.startsWith("+") || s.startsWith("^")) {
                prefix = s.substring(0, 1)
                s = s.substring(1).trim()
            } else if (s.length > 1 && s[0] == 'F' && s[1].isDigit()) {
                prefix = "+"
                s = s.substring(1).trim()
            }
        }

        // Fix leading 'S' misread for '6' when there is no prefix (e.g. "S7.67b" -> "67.67b")
        if (prefix.isEmpty() && s.matches(Regex("^[Ss]\\d.*"))) {
            s = "6" + s.substring(1)
        }

        // Fix leading D. or O. -> 0. (e.g. D.210 -> 0.210)
        if (s.startsWith("D.", ignoreCase = true) || s.startsWith("O.", ignoreCase = true)) {
            s = "0." + s.substring(2)
        }

        // Fix missing leading '1' before decimal point (e.g. ".259" -> "1.259", ".5le7572" -> "1.5le7572")
        if (s.startsWith(".")) {
            s = "1$s"
        }

        // Fix trailing OCR '€' -> '3' and '.0E' -> '.06' (e.g. "1.0€" -> "1.03", "1.0E" -> "1.06")
        s = s.replace('€', '3')
        if (s.endsWith(".0E")) {
            s = s.substring(0, s.length - 1) + "6"
        }

        // Remove internal whitespace around scientific notation 'e' (e.g. "5.25e 6783" -> "5.25e6783")
        s = s.replace(Regex("([0-9A-Za-z])\\s*([eE])\\s*([0-9lIiBDGOozZsS]+)"), "$1$2$3")

        // Fix scientific notation e-component (only when 'e'/'E' is followed by exponent chars)
        val eIdx = s.indexOfFirst { it == 'e' || it == 'E' }
        if (eIdx > 0 && eIdx < s.length - 1) {
            var base = s.substring(0, eIdx)
            var exp = s.substring(eIdx + 1)

            // Fix leading 'J' in base representing "1." (e.g. "J22e105" -> "1.22e105")
            if (base.startsWith("J") && base.length >= 3) {
                base = "1." + base.substring(1)
            }

            // In CIFI scientific notation, base is always d.dd (1 digit, dot, 2 digits)
            if (!base.contains('.')) {
                base = if (base.length == 4 && (base[1] == 'l' || base[1] == 'I' || base[1] == 'i' || base[1] == '1')) {
                    // Dot was misread as 'l'/'1' (e.g. "6l20" -> "6.20")
                    base[0] + "." + base.substring(2)
                } else if (base.length >= 2) {
                    base[0] + "." + base.substring(1)
                } else {
                    base
                }
            }

            // Normalize base digits ('B' at start or after decimal is '6')
            base = base.replace('l', '1').replace('I', '1').replace('i', '1').replace(')', '1')
                .replace('D', '0').replace('O', '0').replace('o', '0').replace('d', '0')
                .replace('B', '6')

            // Normalize exponent digits ('B' and 'G' in CIFI exponent font are '6', e.g. eB02 -> e602, elGD -> e160)
            exp = exp.replace('l', '1').replace('I', '1').replace('i', '1').replace(']', '1').replace(')', '1').replace('j', '1')
                .replace('B', '6').replace('G', '6').replace('b', '0').replace('D', '0').replace('O', '0').replace('o', '0')
                .replace('z', '2').replace('Z', '2').replace('s', '5').replace('S', '5').replace('g', '9')

            s = "${base}e${exp}"
        } else {
            // Normalize 'O'/'D' inside decimal part before suffix extraction (e.g. "552.6O5x" -> "552.605x")
            s = s.replace(Regex("(\\.\\d)[OoDd]"), "$10")

            // Fix CIFI suffix confusion: '5x' or '5X' at end of number is 'sx' (sextillion)
            if (s.endsWith("5x", ignoreCase = true) && s.length > 2) {
                s = s.substring(0, s.length - 2) + "sx"
            }
            // Fix 'ga', '0a', 'Oa' -> 'qa' at end (e.g. 1.10ga -> 1.10qa, 1.200a -> 1.20qa)
            if (s.endsWith("ga", ignoreCase = true) || s.matches(Regex("^\\d+\\.\\d{2}[0Oo]a$", RegexOption.IGNORE_CASE))) {
                s = s.substring(0, s.length - 2) + "qa"
            }
            // Fix 'gm' -> '6m' at end (e.g. 869.6Gm -> 869.66m)
            if (s.endsWith("gm", ignoreCase = true)) {
                s = s.substring(0, s.length - 2) + "6m"
            }
            // Fix 'gk' -> '6k' at end (e.g. 139.2Gk -> 139.26k)
            if (s.endsWith("gk", ignoreCase = true)) {
                s = s.substring(0, s.length - 2) + "6k"
            }
            // Fix 'ik' -> 'k' after 2 decimal places (e.g. 586.51Ik -> 586.51k)
            s = s.replace(Regex("(\\.\\d{2})[iIl1]k$", RegexOption.IGNORE_CASE), "$1k")

            // Fix octillion suffix 'o' misread as '0' after 3-digit integer + 2-digit decimal (e.g. 6BB.970 -> 663.97o, 349.040 -> 349.04o)
            if (s.matches(Regex("^[0-9B]{3}\\.\\d{2}0$"))) {
                s = s.substring(0, s.length - 1) + "o"
            }

            // Suffix handling
            val suffixMatch = Regex("([a-zA-Z]+)$").find(s)
            val suffix = suffixMatch?.groupValues?.get(1) ?: ""
            var numPart = if (suffix.isNotEmpty()) s.substring(0, s.length - suffix.length) else s

            if (numPart.startsWith("6BB")) {
                numPart = "663" + numPart.substring(3)
            } else if (numPart.startsWith("B")) {
                numPart = "8" + numPart.substring(1)
            }

            numPart = numPart.replace('l', '1').replace('I', '1').replace('i', '1').replace(']', '1')
                .replace('O', '0').replace('o', '0').replace('D', '0').replace('G', '6')
            s = numPart + suffix.lowercase()
        }

        // Clean any extraneous spaces or brackets
        s = s.replace(" ", "").replace("[", "").replace("]", "")

        return prefix + s
    }

    /**
     * Cleans rank strings like "6 / 50", "N3/ 50", "N/50", "1Z/30", "1a/999", "10715", "1015"
     */
    fun cleanRank(raw: String, expectedMaxRank: String? = null): String {
        var normalized = raw.trim()
            .replace(Regex("^N(?=\\s*/)"), "11")
            .replace('N', '1')
            .replace('Z', '7')
            .replace('a', '8')
            .replace('D', '0')
            .replace('O', '0')
            .replace('g', '9')

        // Handle slash misread as '7' or dropped before known max rank (e.g. "10715" -> "10 / 15", "1015" -> "10 / 15")
        if (!normalized.contains('/') && expectedMaxRank != null) {
            if (normalized.endsWith("7$expectedMaxRank")) {
                val left = normalized.removeSuffix("7$expectedMaxRank")
                if (left.isNotEmpty() && left.all { it.isDigit() }) {
                    normalized = "$left / $expectedMaxRank"
                }
            } else if (normalized.endsWith(expectedMaxRank) && normalized.length > expectedMaxRank.length) {
                val left = normalized.removeSuffix(expectedMaxRank)
                if (left.isNotEmpty() && left.all { it.isDigit() }) {
                    normalized = "$left / $expectedMaxRank"
                }
            }
        }

        val match = Regex("(\\d+)\\s*/\\s*(\\d+)").find(normalized)
        return if (match != null) {
            val r1 = match.groupValues[1]
            var r2 = match.groupValues[2]
            // Fix OCR typos in max rank (e.g. 99 -> 999, 998 -> 999, 9999 -> 999)
            if (r2 == "99" || r2 == "998" || r2 == "9999") r2 = "999"
            "$r1 / $r2"
        } else {
            raw.trim()
        }
    }
}
