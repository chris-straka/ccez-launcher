package uk.ccez.launcher

import java.text.Normalizer
import kotlin.math.pow

/** Query text matched the same everywhere: case and accents don't count. */
fun normalized(s: String): String {
    val n = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
    return n.replace(Regex("\\p{Mn}+"), "").trim()
}

/** 0: a word starts with the query. 1: the query is inside. -1: no. */
fun matchRank(label: String, query: String): Int {
    if (query.isEmpty()) return 0
    val l = normalized(label)
    if (l.split(Regex("\\s+")).any { it.startsWith(query) }) return 0
    return if (l.contains(query)) 1 else -1
}

/** A typed sum worked out: `2+3*4`, `(80/2)^2`, `10%3`. Nothing else. */
object Calc {
    private val CANDIDATE = Regex("^[0-9+\\-*/().%^×÷\\s]+$")

    fun eval(raw: String): String? {
        val s = raw.trim().replace('×', '*').replace('÷', '/')
        if (s.length < 3 || !CANDIDATE.matches(s)) return null
        if (s.none { it.isDigit() } || s.none { it in "+-*/%^" }) return null
        val tokens = tokenize(s) ?: return null
        val v = Parser(tokens).parse() ?: return null
        if (!v.isFinite()) return null
        return format(v)
    }

    fun format(v: Double): String {
        if (v == v.toLong().toDouble() && kotlin.math.abs(v) < 1e15) return v.toLong().toString()
        var s = "%.10g".format(java.util.Locale.US, v)
        if (s.contains('.') && !s.contains('e')) s = s.trimEnd('0').trimEnd('.')
        return s
    }

    private sealed class T {
        data class Num(val v: Double) : T()
        data class Op(val c: Char) : T()
    }

    private fun tokenize(s: String): List<T>? {
        val out = ArrayList<T>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c == '.' -> {
                    var j = i
                    var dots = 0
                    while (j < s.length && (s[j].isDigit() || s[j] == '.')) {
                        if (s[j] == '.') dots++
                        j++
                    }
                    if (dots > 1) return null
                    out.add(T.Num(s.substring(i, j).toDoubleOrNull() ?: return null))
                    i = j
                }
                c in "+-*/%^()" -> { out.add(T.Op(c)); i++ }
                else -> return null
            }
        }
        return out
    }

    private class Parser(val tokens: List<T>) {
        var i = 0

        fun parse(): Double? {
            val v = expr() ?: return null
            return if (i == tokens.size) v else null
        }

        private fun eat(c: Char): Boolean {
            val t = tokens.getOrNull(i)
            return if (t is T.Op && t.c == c) { i++; true } else false
        }

        private fun expr(): Double? {
            var v = term() ?: return null
            while (true) {
                v = when {
                    eat('+') -> v + (term() ?: return null)
                    eat('-') -> v - (term() ?: return null)
                    else -> return v
                }
            }
        }

        private fun term(): Double? {
            var v = factor() ?: return null
            while (true) {
                v = when {
                    eat('*') -> v * (factor() ?: return null)
                    eat('/') -> {
                        val d = factor() ?: return null
                        if (d == 0.0) return null
                        v / d
                    }
                    eat('%') -> v % (factor() ?: return null)
                    else -> return v
                }
            }
        }

        private fun factor(): Double? {
            val base = unary() ?: return null
            return if (eat('^')) base.pow(factor() ?: return null) else base
        }

        private fun unary(): Double? = when {
            eat('-') -> unary()?.let { -it }
            eat('+') -> unary()
            eat('(') -> {
                val v = expr() ?: return null
                if (eat(')')) v else null
            }
            else -> (tokens.getOrNull(i++) as? T.Num)?.v
        }
    }
}

/** `5 km to mi`, `72 f to c`, `1.5 lb to g`: the everyday conversions. */
object Units {
    private val FORM = Regex("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*([a-zA-Z°]+)\\s+(?:to|in)\\s+([a-zA-Z°]+)\\s*$")

    private class Group(val toBase: Map<String, Double>, val names: Map<String, String>)

    private fun group(vararg units: Triple<String, String, Double>): Group {
        val toBase = HashMap<String, Double>()
        val names = HashMap<String, String>()
        for ((sym, aliases, f) in units) {
            toBase[sym] = f
            names[sym] = sym
            for (a in aliases.split(",")) if (a.isNotBlank()) names[a.trim()] = sym
        }
        return Group(toBase, names)
    }

    private val GROUPS = listOf(
        group(
            Triple("mm", "millimetre,millimetres,millimeter,millimeters", 0.001),
            Triple("cm", "centimetre,centimetres,centimeter,centimeters", 0.01),
            Triple("m", "metre,metres,meter,meters", 1.0),
            Triple("km", "kilometre,kilometres,kilometer,kilometers", 1000.0),
            Triple("in", "inch,inches", 0.0254),
            Triple("ft", "foot,feet", 0.3048),
            Triple("yd", "yard,yards", 0.9144),
            Triple("mi", "mile,miles", 1609.344),
        ),
        group(
            Triple("mg", "milligram,milligrams", 0.001),
            Triple("g", "gram,grams,gramme,grammes", 1.0),
            Triple("kg", "kilogram,kilograms,kilo,kilos", 1000.0),
            Triple("t", "tonne,tonnes,ton,tons", 1_000_000.0),
            Triple("oz", "ounce,ounces", 28.349523125),
            Triple("lb", "pound,pounds,lbs", 453.59237),
        ),
        group(
            Triple("ml", "millilitre,millilitres,milliliter,milliliters", 0.001),
            Triple("l", "litre,litres,liter,liters", 1.0),
            Triple("gal", "gallon,gallons", 3.785411784),
            Triple("floz", "fl oz,fluidounce,fluidounces", 0.0295735295625),
            Triple("cup", "cups", 0.2365882365),
        ),
        group(
            Triple("ms", "millisecond,milliseconds", 0.001),
            Triple("s", "second,seconds,sec,secs", 1.0),
            Triple("min", "minute,minutes,mins", 60.0),
            Triple("h", "hour,hours,hr,hrs", 3600.0),
            Triple("d", "day,days", 86400.0),
            Triple("wk", "week,weeks", 604800.0),
        ),
        group(
            Triple("m/s", "mps", 1.0),
            Triple("km/h", "kmh,kph", 1 / 3.6),
            Triple("mph", "", 0.44704),
        ),
        group(
            Triple("b", "byte,bytes", 1.0),
            Triple("kb", "kilobyte,kilobytes", 1000.0),
            Triple("mb", "megabyte,megabytes", 1e6),
            Triple("gb", "gigabyte,gigabytes", 1e9),
            Triple("tb", "terabyte,terabytes", 1e12),
            Triple("kib", "", 1024.0),
            Triple("mib", "", 1024.0 * 1024),
            Triple("gib", "", 1024.0 * 1024 * 1024),
        ),
    )

    private val TEMPS = mapOf(
        "c" to "c", "°c" to "c", "celsius" to "c", "centigrade" to "c",
        "f" to "f", "°f" to "f", "fahrenheit" to "f",
        "k" to "k", "kelvin" to "k",
    )

    data class Result(val text: String, val value: String, val unit: String)

    /** Six figures read better than ten for a conversion. */
    private fun fmt(v: Double): String {
        if (v == v.toLong().toDouble() && kotlin.math.abs(v) < 1e15) return v.toLong().toString()
        var s = "%.6g".format(java.util.Locale.US, v)
        if (s.contains('.') && !s.contains('e')) s = s.trimEnd('0').trimEnd('.')
        return s
    }

    fun convert(raw: String): Result? {
        val m = FORM.matchEntire(raw) ?: return null
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        val from = m.groupValues[2].lowercase()
        val to = m.groupValues[3].lowercase()
        val tf = TEMPS[from]
        val tt = TEMPS[to]
        if (tf != null || tt != null) {
            if (tf == null || tt == null) return null
            val c = when (tf) { "c" -> v; "f" -> (v - 32) * 5 / 9; else -> v - 273.15 }
            val out = when (tt) { "c" -> c; "f" -> c * 9 / 5 + 32; else -> c + 273.15 }
            val unit = if (tt == "k") "K" else "°" + tt.uppercase()
            return Result("${fmt(v)} ${label(tf, from)} = ${fmt(out)} $unit", fmt(out), unit)
        }
        for (g in GROUPS) {
            val fs = g.names[from] ?: continue
            val ts = g.names[to] ?: continue
            val out = v * g.toBase.getValue(fs) / g.toBase.getValue(ts)
            return Result("${fmt(v)} $fs = ${fmt(out)} $ts", fmt(out), ts)
        }
        return null
    }

    private fun label(sym: String, typed: String): String =
        if (sym == "k") "K" else if (typed.startsWith("°")) typed else sym
}
