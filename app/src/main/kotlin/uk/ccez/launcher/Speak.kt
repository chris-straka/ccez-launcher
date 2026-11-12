package uk.ccez.launcher

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

/** The learning language of the day: they take turns daily (Stage 5). */
object LangOfDay {
    fun today(langs: List<String>): String? {
        if (langs.isEmpty()) return null
        val i = (LocalDate.now().toEpochDay() % langs.size).toInt()
        return langs[i]
    }

    fun todayFromCore(): String? {
        val e = Words.engineOrNull() ?: return null
        val langs = runCatching { e.settingStrings("languages.learning") }.getOrDefault(emptyList())
        return today(langs)
    }
}

/**
 * The time and date written out in a language ("Il est quinze heures
 * douze", "mercredi onze novembre"). Only hours, minutes and days are
 * ever spelled (0–59), with small tables per language — ICU's spellout
 * formatter is not in the public SDK and reflection to it is blocked.
 * Languages without a table keep digits.
 */
object ClockWords {
    fun spell(lang: String, n: Int): String {
        if (n !in 0..59) return n.toString()
        return when (lang) {
            "en" -> spellEn(n)
            "fr" -> spellFr(n)
            "de" -> spellDe(n)
            "es" -> spellEs(n)
            "pt" -> spellPt(n)
            "it" -> spellIt(n)
            "ja" -> spellCjk(n, "〇一二三四五六七八九十")
            "zh" -> spellCjk(n, "零一二三四五六七八九十")
            "ko" -> spellKo(n)
            "id" -> spellId(n)
            "tr" -> spellTr(n)
            "ru" -> spellRu(n)
            "pl" -> spellPl(n)
            "no" -> spellNo(n)
            else -> n.toString()
        }
    }

    private fun spellEn(n: Int): String {
        val ones = listOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
            "seventeen", "eighteen", "nineteen",
        )
        val tens = listOf("", "", "twenty", "thirty", "forty", "fifty")
        return if (n < 20) ones[n] else tens[n / 10] + if (n % 10 == 0) "" else "-" + ones[n % 10]
    }

    private fun spellFr(n: Int): String {
        val ones = listOf(
            "zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf",
            "dix", "onze", "douze", "treize", "quatorze", "quinze", "seize",
            "dix-sept", "dix-huit", "dix-neuf",
        )
        val tens = listOf("", "", "vingt", "trente", "quarante", "cinquante")
        if (n < 20) return ones[n]
        val r = n % 10
        return when (r) {
            0 -> tens[n / 10]
            1 -> "${tens[n / 10]} et un"
            else -> "${tens[n / 10]}-${ones[r]}"
        }
    }

    private fun spellDe(n: Int): String {
        val ones = listOf(
            "null", "eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun",
            "zehn", "elf", "zwölf", "dreizehn", "vierzehn", "fünfzehn", "sechzehn",
            "siebzehn", "achtzehn", "neunzehn",
        )
        val tens = listOf("", "", "zwanzig", "dreißig", "vierzig", "fünfzig")
        if (n < 20) return ones[n]
        val r = n % 10
        if (r == 0) return tens[n / 10]
        val first = if (r == 1) "ein" else ones[r]
        return first + "und" + tens[n / 10]
    }

    private fun spellEs(n: Int): String {
        val ones = listOf(
            "cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve",
            "diez", "once", "doce", "trece", "catorce", "quince",
        )
        if (n <= 15) return ones[n]
        if (n < 20) return "dieci" + ones[n - 10].replace("siete", "siete")
        if (n == 20) return "veinte"
        if (n < 30) return "veinti" + ones[n - 20]
        val tens = listOf("", "", "", "treinta", "cuarenta", "cincuenta")
        val r = n % 10
        return if (r == 0) tens[n / 10] else "${tens[n / 10]} y ${ones[r]}"
    }

    private fun spellPt(n: Int): String {
        val ones = listOf(
            "zero", "um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove",
            "dez", "onze", "doze", "treze", "catorze", "quinze", "dezesseis", "dezessete",
            "dezoito", "dezenove",
        )
        val tens = listOf("", "", "vinte", "trinta", "quarenta", "cinquenta")
        if (n < 20) return ones[n]
        val r = n % 10
        return if (r == 0) tens[n / 10] else "${tens[n / 10]} e ${ones[r]}"
    }

    private fun spellIt(n: Int): String {
        val ones = listOf(
            "zero", "uno", "due", "tre", "quattro", "cinque", "sei", "sette", "otto", "nove",
            "dieci", "undici", "dodici", "tredici", "quattordici", "quindici", "sedici",
            "diciassette", "diciotto", "diciannove",
        )
        val tens = listOf("", "", "venti", "trenta", "quaranta", "cinquanta")
        if (n < 20) return ones[n]
        val r = n % 10
        if (r == 0) return tens[n / 10]
        val head = tens[n / 10]
        val base = if (r == 1 || r == 8) head.dropLast(1) else head
        return base + ones[r]
    }

    private fun spellCjk(n: Int, digits: String): String {
        val ten = digits[10].toString()
        if (n < 10) return digits[n].toString()
        val t = n / 10
        val r = n % 10
        val head = if (t == 1) ten else digits[t].toString() + ten
        return if (r == 0) head else head + digits[r]
    }

    private fun spellKo(n: Int): String {
        val d = listOf("영", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구", "십")
        if (n <= 10) return d[n]
        val t = n / 10
        val r = n % 10
        val head = if (t == 1) "십" else d[t] + "십"
        return if (r == 0) head else head + d[r]
    }

    private fun spellId(n: Int): String {
        val ones = listOf(
            "nol", "satu", "dua", "tiga", "empat", "lima", "enam", "tujuh", "delapan",
            "sembilan", "sepuluh", "sebelas",
        )
        if (n <= 11) return ones[n]
        if (n < 20) return ones[n - 10] + " belas"
        val t = n / 10
        val r = n % 10
        return ones[t] + " puluh" + if (r == 0) "" else " " + ones[r]
    }

    private fun spellTr(n: Int): String {
        val ones = listOf("sıfır", "bir", "iki", "üç", "dört", "beş", "altı", "yedi", "sekiz", "dokuz", "on")
        val tens = listOf("", "on", "yirmi", "otuz", "kırk", "elli")
        if (n <= 10) return ones[n]
        val r = n % 10
        return tens[n / 10] + if (r == 0) "" else " " + ones[r]
    }

    private fun spellRu(n: Int): String {
        val ones = listOf(
            "ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять",
            "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать",
            "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать",
        )
        val tens = listOf("", "", "двадцать", "тридцать", "сорок", "пятьдесят")
        if (n < 20) return ones[n]
        val r = n % 10
        return tens[n / 10] + if (r == 0) "" else " " + ones[r]
    }

    private fun spellPl(n: Int): String {
        val ones = listOf(
            "zero", "jeden", "dwa", "trzy", "cztery", "pięć", "sześć", "siedem", "osiem",
            "dziewięć", "dziesięć", "jedenaście", "dwanaście", "trzynaście", "czternaście",
            "piętnaście", "szesnaście", "siedemnaście", "osiemnaście", "dziewiętnaście",
        )
        val tens = listOf("", "", "dwadzieścia", "trzydzieści", "czterdzieści", "pięćdziesiąt")
        if (n < 20) return ones[n]
        val r = n % 10
        return tens[n / 10] + if (r == 0) "" else " " + ones[r]
    }

    private fun spellNo(n: Int): String {
        val ones = listOf(
            "null", "en", "to", "tre", "fire", "fem", "seks", "sju", "åtte", "ni", "ti",
            "elleve", "tolv", "tretten", "fjorten", "femten", "seksten", "sytten", "atten", "nitten",
        )
        val tens = listOf("", "", "tjue", "tretti", "førti", "femti")
        if (n < 20) return ones[n]
        val r = n % 10
        return tens[n / 10] + if (r == 0) "" else ones[r]
    }

    fun time(lang: String, time: LocalDateTime): String {
        val h = time.hour
        val m = time.minute
        val hs = spell(lang, h)
        val ms = if (m == 0) "" else spell(lang, m)
        val text = when (lang) {
            "fr" -> {
                val hour = if (h == 1) "une heure" else "$hs heures"
                if (m == 0) "Il est $hour" else "Il est $hour $ms"
            }
            "de" -> if (m == 0) "Es ist $hs Uhr" else "Es ist $hs Uhr $ms"
            "es" -> {
                val base = if (h == 1) "Es la una" else "Son las $hs"
                if (m == 0) base else "$base y $ms"
            }
            "it" -> {
                val base = if (h == 1) "È l'una" else "Sono le $hs"
                if (m == 0) base else "$base e $ms"
            }
            "pt" -> {
                val base = if (h == 1) "É uma" else "São $hs"
                if (m == 0) base else "$base e $ms"
            }
            "ja" -> if (m == 0) "${hs}時" else "${hs}時${ms}分"
            "zh" -> if (m == 0) "${hs}点" else "${hs}点${ms}分"
            "ko" -> if (m == 0) "${hs}시" else "${hs}시 ${ms}분"
            else -> listOf(hs, ms).filter { it.isNotEmpty() }.joinToString(" ")
        }
        return text.replaceFirstChar { it.uppercase() }
    }

    fun date(lang: String, day: LocalDate): String {
        val loc = Locale.forLanguageTag(lang)
        val weekday = day.format(DateTimeFormatter.ofPattern("EEEE", loc))
        val month = day.format(DateTimeFormatter.ofPattern("MMMM", loc))
        val d = spell(lang, day.dayOfMonth)
        return when (lang) {
            "ja", "zh" -> "${spell(lang, day.monthValue)}月${d}日、$weekday"
            "ko" -> "${spell(lang, day.monthValue)}월 ${d}일 $weekday"
            else -> "$weekday $d $month"
        }
    }
}

/** Says text with the phone's best voice for the language (as ccez-keys). Only ever made on a tap. */
object Speaker {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var pending: Pair<String, String>? = null
    private val voices = java.util.concurrent.ConcurrentHashMap<String, Voice>()
    private var current: String? = null

    private fun engine(ctx: Context): TextToSpeech = tts ?: TextToSpeech(ctx.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (!ready) {
            tts?.shutdown()
            tts = null
            return@TextToSpeech
        }
        pending?.let { (s, l) -> speak(s, l) }
        pending = null
    }.also { tts = it }

    fun say(ctx: Context, text: String, lang: String) {
        engine(ctx)
        if (!ready) {
            pending = text to lang
            return
        }
        speak(text, lang)
    }

    private fun use(t: TextToSpeech, lang: String): Boolean {
        if (current == lang) return true
        val want = Locale.forLanguageTag(lang).language
        val voice = voices[lang] ?: runCatching { t.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == want && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
            .maxWithOrNull(compareBy<Voice>({ it.quality }, { !it.isNetworkConnectionRequired }))
            ?.also { voices[lang] = it }
            ?: return false
        t.voice = voice
        t.setSpeechRate(0.9f)
        current = lang
        return true
    }

    private fun speak(text: String, lang: String) {
        val t = tts ?: return
        if (!use(t, lang)) return
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "clock")
    }
}

/**
 * App names in the learning language of the day: translated once with
 * ML Kit on the phone and cached (files/launcher/app-names.json).
 * The usual name stays in menus (a hold shows it).
 */
object AppNames {
    private val ONE = Mutex()
    val shown = MutableStateFlow<Map<String, String>>(emptyMap())
    @Volatile private var shownLang: String? = null
    @Volatile private var shownDay: Long = Long.MIN_VALUE

    /** The name to show for an app right now (its usual one when off). */
    fun nameFor(entry: AppEntry): String = shown.value[entry.key] ?: entry.label

    fun maybeRefresh(ctx: Context) {
        val app = App.instance
        if (!app.settings.bool("words.app_name_langs")) {
            if (shown.value.isNotEmpty()) shown.value = emptyMap()
            return
        }
        val day = LocalDate.now().toEpochDay()
        if (day == shownDay && shown.value.isNotEmpty()) return
        app.scope.launch(Dispatchers.IO) {
            runCatching { refresh(ctx) }
                .onFailure { app.launcherLog.log("warn", "app-names-failed", "error" to it.toString()) }
        }
    }

    suspend fun refresh(ctx: Context) {
        val app = App.instance
        val e = Words.engine(ctx)
        val langs = runCatching { e.settingStrings("languages.learning") }.getOrDefault(emptyList())
        val lang = LangOfDay.today(langs) ?: return
        val target = TranslateLanguage.fromLanguageTag(lang) ?: return
        val apps = app.apps.apps.value
        if (apps.isEmpty()) return
        ONE.withLock {
            val file = File(ctx.filesDir, "launcher/app-names.json")
            val cache: JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
            val perLang: JSONObject = cache.optJSONObject(lang) ?: JSONObject().also { cache.put(lang, it) }
            val byLabel = HashMap<String, String>()
            fun cached(component: String, label: String): String? =
                perLang.optString(component).ifEmpty { null } ?: byLabel[label]
            val missing = apps.filter { cached(componentOf(it), it.label) == null }
            if (missing.isNotEmpty()) {
                val manager = RemoteModelManager.getInstance()
                val model = TranslateRemoteModel.Builder(target).build()
                if (!manager.isModelDownloaded(model).await()) {
                    manager.download(model, DownloadConditions.Builder().build()).await()
                }
                val client = Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(TranslateLanguage.ENGLISH)
                        .setTargetLanguage(target)
                        .build(),
                )
                try {
                    for (entry in missing) {
                        val got = byLabel[entry.label] ?: runCatching { client.translate(entry.label).await() }.getOrNull()
                        if (!got.isNullOrBlank()) {
                            byLabel[entry.label] = got
                            perLang.put(componentOf(entry), got)
                        }
                    }
                } finally {
                    client.close()
                }
                writeSafely(file, cache.toString())
            }
            val out = HashMap<String, String>()
            for (entry in apps) {
                cached(componentOf(entry), entry.label)?.let { out[entry.key] = it }
            }
            shown.value = out
            shownLang = lang
            shownDay = LocalDate.now().toEpochDay()
            app.launcherLog.log("info", "app-names", "lang" to lang, "count" to out.size)
        }
    }

    private fun componentOf(e: AppEntry): String = "${e.id.pkg}/${e.id.cls}"
}
