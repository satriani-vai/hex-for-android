package app.hexforandroid

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The models Hex for Android can install, each pinned exactly as desktop
 * Hex's catalog pins it (`src/transcription/artifacts.rs` in this
 * repository): the same Hugging Face revision and the same SHA-256, so a
 * file downloaded here is the file desktop Hex verifies.
 *
 * Each model is its own file in the app's external files directory, so a
 * development machine can also `adb push` one there instead of downloading
 * it, and a few can sit side by side (about 700 MB each).
 */
object ModelStore {
    /** The choice that lets the model hear the language itself. */
    const val AUTO = "auto"

    /** One model in the picker. */
    data class Model(
        val id: String,
        val name: String,
        val filename: String,
        val repository: String,
        val revision: String,
        val bytes: Long,
        val sha256: String,
        /** transcribe.cpp's architecture and variant, for the engine's log line. */
        val architecture: String,
        val variant: String,
        /** The ISO codes it transcribes. */
        val languages: List<String>,
        /** Whether the model hears the language from the audio, so Auto works. */
        val detects: Boolean,
        /**
         * Whether a run takes a language hint. Nemotron's prompt is
         * conditioned on it, so it must hear one; Parakeet v3 hears the
         * language itself and takes none.
         */
        val acceptsHint: Boolean,
        /** The locales a language-prompted model takes, by ISO code. */
        val locales: Map<String, String> = emptyMap(),
        /** One line under the model's name in the picker. */
        val summary: String,
    ) {
        val url: String get() = "https://huggingface.co/$repository/resolve/$revision/$filename"

        val sizeLabel: String get() = "${bytes / 1_000_000} MB"

        /** What the language sheet offers: Auto first when the model detects. */
        fun languageChoices(): List<String> =
            (if (detects) listOf(AUTO) else emptyList()) + languages

        /**
         * The hint a run in [language] takes, or `null` when the model
         * needs none (Parakeet v3 hears the language itself) or the choice
         * is Auto.
         */
        fun hintFor(language: String): String? = when {
            !acceptsHint || language == AUTO -> null
            else -> locales[language] ?: language.takeIf { it in languages }
        }
    }

    /**
     * Desktop Hex's recommended English model: the fastest one it ships,
     * and what Hex for Android has always run.
     */
    val English = Model(
        id = "parakeet_unified_en",
        name = "Parakeet Unified English",
        filename = "parakeet-unified-en-0.6b-Q8_0.gguf",
        repository = "handy-computer/parakeet-unified-en-0.6b-gguf",
        revision = "7e948f21b7bdbac698d3318db9d350f1096f3b6c",
        bytes = 731_357_568L,
        sha256 = "4b50b6dd862bf6e346929aaf4f5eaacec003bfa3f56462d6c874b41ef2f38795",
        architecture = "parakeet",
        variant = "unified-en-0.6b",
        languages = listOf("en"),
        detects = false,
        acceptsHint = true,
        summary = "Desktop Hex's English model · the fastest",
    )

    /**
     * NVIDIA's Parakeet TDT 0.6B v3, multilingual over 25 European
     * languages including German and Russian. It hears which language is
     * spoken, so there is nothing to choose.
     */
    val ParakeetV3 = Model(
        id = "parakeet_v3",
        name = "Parakeet v3",
        filename = "parakeet-tdt-0.6b-v3-Q8_0.gguf",
        repository = "handy-computer/parakeet-tdt-0.6b-v3-gguf",
        revision = "85ac09ea12fc4b1112fa76810059364bc6adc9de",
        bytes = 739_508_576L,
        sha256 = "5859f77944efcd8eafa23a6350731960b2b55b2203df51f319665c807d802cc7",
        architecture = "parakeet",
        variant = "tdt-0.6b-v3",
        languages = listOf(
            "bg", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "de", "el", "hu", "it", "lv", "lt",
            "mt", "pl", "pt", "ro", "ru", "sk", "sl", "es", "sv", "uk",
        ),
        detects = true,
        acceptsHint = false,
        summary = "25 languages, German and Russian included · hears which one",
    )

    /**
     * NVIDIA's Nemotron 3.5 ASR Streaming 0.6B, a cache-aware FastConformer
     * whose language prompt takes a locale, so the run is told which
     * language to expect (or left to detect it).
     */
    val Nemotron = Model(
        id = "nemotron_3_5_asr",
        name = "Nemotron 3.5 ASR",
        filename = "nemotron-3.5-asr-streaming-0.6b-Q8_0.gguf",
        repository = "handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf",
        revision = "53ce288f69326b415aeef030cfcb02a08a8096dd",
        bytes = 751_094_240L,
        sha256 = "b94545b313b3223fda7b2857a52681da813935c2127643d1e9ff0c23d988089c",
        architecture = "parakeet",
        variant = "nemotron-3.5-asr-streaming-0.6b",
        languages = listOf(
            "en", "es", "fr", "de", "it", "pt", "nl", "tr", "ru", "uk", "pl", "cs", "sk", "bg", "hr",
            "ro", "hu", "sv", "da", "fi", "et", "ar", "hi", "ja", "ko", "zh", "vi",
        ),
        detects = true,
        acceptsHint = true,
        locales = mapOf(
            "en" to "en-US", "es" to "es-ES", "fr" to "fr-FR", "de" to "de-DE", "it" to "it-IT",
            "pt" to "pt-BR", "nl" to "nl-NL", "tr" to "tr-TR", "ru" to "ru-RU", "uk" to "uk-UA",
            "pl" to "pl-PL", "cs" to "cs-CZ", "sk" to "sk-SK", "bg" to "bg-BG", "hr" to "hr-HR",
            "ro" to "ro-RO", "hu" to "hu-HU", "sv" to "sv-SE", "da" to "da-DK", "fi" to "fi-FI",
            "et" to "et-EE", "ar" to "ar-AR", "hi" to "hi-IN", "ja" to "ja-JP", "ko" to "ko-KR",
            "zh" to "zh-CN", "vi" to "vi-VN",
        ),
        summary = "27 languages, German and Russian included · takes the language you pick",
    )

    /** The picker's order: the English model first, then the multilingual ones. */
    val models = listOf(English, ParakeetV3, Nemotron)

    /** The saved model, or [English] when this build or the setting doesn't know the id. */
    fun of(id: String?): Model = models.firstOrNull { it.id == id } ?: English

    /** Display names for the languages the models transcribe. */
    val languageNames = mapOf(
        "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German", "it" to "Italian",
        "pt" to "Portuguese", "nl" to "Dutch", "tr" to "Turkish", "ru" to "Russian",
        "uk" to "Ukrainian", "pl" to "Polish", "cs" to "Czech", "sk" to "Slovak",
        "bg" to "Bulgarian", "hr" to "Croatian", "ro" to "Romanian", "hu" to "Hungarian",
        "sv" to "Swedish", "da" to "Danish", "fi" to "Finnish", "et" to "Estonian",
        "ar" to "Arabic", "hi" to "Hindi", "ja" to "Japanese", "ko" to "Korean",
        "zh" to "Mandarin Chinese", "vi" to "Vietnamese", "el" to "Greek", "lv" to "Latvian",
        "lt" to "Lithuanian", "mt" to "Maltese", "sl" to "Slovenian",
    )

    fun languageName(code: String) = languageNames[code] ?: code

    /** What the language row and sheet call a choice. */
    fun languageLabel(code: String) = if (code == AUTO) "Auto" else languageName(code)

    /**
     * [language] when this model speaks it, else what it falls back to, so a
     * switch stays valid.
     *
     * A model that hears the language itself starts on [AUTO]: `en` cannot be
     * told apart from the never-touched default, and every such model detects
     * English anyway, so keeping `en` would quietly force English on a model
     * whose run takes a language prompt (Nemotron).
     */
    fun languageFor(model: Model, language: String): String = when {
        model.detects && language != AUTO && language != "en" && language in model.languages -> language
        model.detects -> AUTO
        language in model.languages -> language
        model.languages.contains("en") -> "en"
        else -> model.languages.first()
    }

    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }

    fun file(context: Context, model: Model) = File(dir(context), model.filename)

    private fun part(context: Context, model: Model) = File(dir(context), "${model.filename}.part")

    private fun marker(context: Context, model: Model) = File(dir(context), "${model.filename}.verified")

    /** True when the file is present and its hash was checked for this size and time. */
    fun isVerified(context: Context, model: Model): Boolean {
        val file = file(context, model)
        if (!file.isFile || file.length() != model.bytes) return false
        val marker = marker(context, model)
        return marker.isFile && marker.readText() == stamp(file, model)
    }

    fun isPresent(context: Context, model: Model) = file(context, model).let { it.isFile && it.length() == model.bytes }

    private fun stamp(file: File, model: Model) = "${file.length()}:${file.lastModified()}:${model.sha256}"

    /** Hashes the file (a few seconds) and records the result. */
    fun verify(context: Context, model: Model, progress: (Float) -> Unit = {}): Boolean {
        val file = file(context, model)
        if (!file.isFile || file.length() != model.bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 20)
        var read = 0L
        FileInputStream(file).use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                read += n
                progress(read.toFloat() / model.bytes)
            }
        }
        val ok = digest.digest().joinToString("") { "%02x".format(it) } == model.sha256
        if (ok) marker(context, model).writeText(stamp(file, model)) else marker(context, model).delete()
        return ok
    }

    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Downloads with resume from a partial file, then verifies. Blocking; one at a time. */
    fun download(context: Context, model: Model, progress: (Long, Long) -> Unit): Boolean {
        if (!busy.compareAndSet(false, true)) throw IllegalStateException("A download is already running")
        try {
            return downloadOnce(context, model, progress)
        } finally {
            busy.set(false)
        }
    }

    private fun downloadOnce(context: Context, model: Model, progress: (Long, Long) -> Unit): Boolean {
        val part = part(context, model)
        var have = if (part.isFile) part.length() else 0L
        if (have > model.bytes) {
            part.delete()
            have = 0
        }
        if (have < model.bytes) {
            val connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 30_000
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            val code = connection.responseCode
            if (code == 200 && have > 0) have = 0 // The server ignored the range.
            if (code != 200 && code != 206) throw IllegalStateException("HTTP $code from Hugging Face")
            connection.inputStream.use { input ->
                FileOutputStream(part, have > 0).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var last = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        have += n
                        if (have - last > (1 shl 20)) {
                            progress(have, model.bytes)
                            last = have
                        }
                    }
                }
            }
        }
        // A stream that ended early keeps its partial file for the next resume.
        if (have != model.bytes) throw IllegalStateException("The download stopped at ${have shr 20} MB; try again to resume")
        progress(model.bytes, model.bytes)
        val file = file(context, model)
        file.delete()
        if (!part.renameTo(file)) throw IllegalStateException("could not move the download into place")
        return verify(context, model)
    }

    /** Forgets a model's files, so the phone keeps the space. The choice falls back if it was this one. */
    fun remove(context: Context, model: Model) {
        part(context, model).delete()
        marker(context, model).delete()
        file(context, model).delete()
    }
}