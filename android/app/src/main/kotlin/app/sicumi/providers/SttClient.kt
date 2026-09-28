package app.sicumi.providers

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Пакетное распознавание речи у выбранного провайдера.
 * smart = true — провайдер сам убирает слова-паразиты и самоисправления (сейчас умеет только Gemini).
 */
object SttClient {

    private const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta"
    private const val OPENAI_BASE = "https://api.openai.com/v1"
    private const val GROQ_BASE = "https://api.groq.com/openai/v1"
    private const val SONIOX_BASE = "https://api.soniox.com/v1"

    /** Лимит встроенных данных Gemini — 20 МБ на весь запрос (base64 раздувает файл на треть). */
    private const val GEMINI_INLINE_LIMIT = 14L * 1024 * 1024

    /** Опрос Soniox раз в секунду, не дольше часа. */
    private const val SONIOX_MAX_POLLS = 3600

    /** Провайдеры, которые сами выдают «чистый» текст и не нуждаются в LLM-очистке. */
    fun producesCleanText(provider: AiProvider): Boolean = provider.id == "gemini"

    suspend fun transcribe(
        provider: AiProvider,
        key: String,
        model: String,
        audio: File,
        mime: String = "audio/wav",
        smart: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val text = when (provider.id) {
            "gemini" -> gemini(key, model, audio, mime, smart)
            "openai" -> openAiCompatible("$OPENAI_BASE/audio/transcriptions", key, audio, mime, model)
            "groq" -> openAiCompatible("$GROQ_BASE/audio/transcriptions", key, audio, mime, model)
            "soniox" -> soniox(key, model, audio, mime)
            else -> throw IllegalArgumentException("STT is not supported by ${provider.id}")
        }
        text.trim()
    }

    /**
     * Транскрипция куска встречи с разметкой дикторов, где провайдер это умеет.
     * Время реплик — от начала переданного файла. Метки дикторов действуют только внутри одного файла.
     */
    suspend fun transcribeMeeting(
        provider: AiProvider,
        key: String,
        model: String,
        audio: File,
        mime: String,
    ): List<TranscriptSegment> = withContext(Dispatchers.IO) {
        when (provider.id) {
            "gemini" -> geminiDiarized(key, model, audio, mime)
            "openai" -> openAiMeeting(key, model, audio, mime)
            "groq" -> whisperSegments("$GROQ_BASE/audio/transcriptions", key, model, audio, mime)
            "soniox" -> sonioxDiarized(key, model, audio, mime)
            else -> throw IllegalArgumentException("STT is not supported by ${provider.id}")
        }.filter { it.text.isNotBlank() }
    }

    // --- Gemini: Interactions API (модели транскрипции, например gemini-3.5-transcribe) ---

    private fun gemini(key: String, model: String, audio: File, mime: String, smart: Boolean): String {
        val transcription = JSONObject().put("language_codes", JSONArray().put("he-IL"))
        if (smart) transcription.put("mode", "smart")
        return geminiText(geminiRequest(key, model, audio, mime, transcription))
    }

    private fun geminiDiarized(key: String, model: String, audio: File, mime: String): List<TranscriptSegment> {
        val transcription = JSONObject()
            .put("language_codes", JSONArray().put("he-IL"))
            .put(
                "mode",
                JSONObject()
                    .put("type", "verbatim")
                    .put("diarization_mode", "speaker")
                    .put("timestamp_granularities", JSONArray().put("word")),
            )
        val json = geminiRequest(key, model, audio, mime, transcription)
        val words = mutableListOf<Word>()
        val steps = json.optJSONArray("steps") ?: JSONArray()
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            if (step.optString("type") != "model_output") continue
            val content = step.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val annotations = content.optJSONObject(j)?.optJSONArray("annotations") ?: continue
                for (k in 0 until annotations.length()) {
                    val a = annotations.optJSONObject(k) ?: continue
                    if (a.optString("type") != "word_info") continue
                    words += Word(
                        text = a.optString("text"),
                        speaker = a.optString("speaker").ifBlank { null },
                        startMs = seconds(a.optString("start_offset")),
                        spaced = true,
                    )
                }
            }
        }
        // Без аннотаций — хотя бы весь текст одним куском.
        if (words.isEmpty()) return listOf(TranscriptSegment(null, 0, geminiText(json)))
        return groupWords(words)
    }

    /** "1.250s" → 1250 */
    private fun seconds(value: String): Long =
        ((value.removeSuffix("s").toDoubleOrNull() ?: 0.0) * 1000).toLong()

    private fun geminiRequest(key: String, model: String, audio: File, mime: String, transcription: JSONObject): JSONObject {
        if (audio.length() > GEMINI_INLINE_LIMIT) throw ApiException(413, "audio too large for inline request")
        val data = Base64.encodeToString(audio.readBytes(), Base64.NO_WRAP)
        val body = JSONObject()
            .put("model", model)
            .put("store", false)
            .put(
                "input",
                JSONArray().put(
                    JSONObject().put("type", "audio").put("data", data).put("mime_type", mime),
                ),
            )
            .put("generation_config", JSONObject().put("transcription_config", transcription))
        val response = Http.postJson("$GEMINI_BASE/interactions", mapOf("x-goog-api-key" to key), body.toString())
        return JSONObject(response)
    }

    /** Текст — в steps[type=model_output].content[type=text].text. */
    private fun geminiText(json: JSONObject): String {
        val steps = json.optJSONArray("steps") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            if (step.optString("type") != "model_output") continue
            val content = step.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") == "text") sb.append(part.optString("text"))
            }
        }
        return sb.toString()
    }

    // --- OpenAI и Groq: /audio/transcriptions, multipart ---

    private fun openAiCompatible(url: String, key: String, audio: File, mime: String, model: String): String {
        val response = Http.postMultipart(
            url = url,
            headers = mapOf("Authorization" to "Bearer $key"),
            fields = mapOf("model" to model, "language" to "he", "response_format" to "json"),
            fileField = "file",
            file = audio,
            fileMime = mime,
        )
        return JSONObject(response).optString("text")
    }

    /**
     * Встреча через OpenAI. Что вернётся, зависит от модели, которую выбрал пользователь:
     * модели с «diarize» различают дикторов, whisper отдаёт реплики со временем, остальные — только текст.
     */
    private fun openAiMeeting(key: String, model: String, audio: File, mime: String): List<TranscriptSegment> = when {
        "diarize" in model -> openAiDiarized(key, model, audio, mime)
        model.startsWith("whisper") -> whisperSegments("$OPENAI_BASE/audio/transcriptions", key, model, audio, mime)
        else -> listOf(TranscriptSegment(null, 0, openAiCompatible("$OPENAI_BASE/audio/transcriptions", key, audio, mime, model)))
    }

    private fun openAiDiarized(key: String, model: String, audio: File, mime: String): List<TranscriptSegment> {
        val response = Http.postMultipart(
            url = "$OPENAI_BASE/audio/transcriptions",
            headers = mapOf("Authorization" to "Bearer $key"),
            fields = mapOf(
                "model" to model,
                "language" to "he",
                "response_format" to "diarized_json",
                "chunking_strategy" to "auto",
            ),
            fileField = "file",
            file = audio,
            fileMime = mime,
        )
        val json = JSONObject(response)
        val segments = json.optJSONArray("segments") ?: return listOf(TranscriptSegment(null, 0, json.optString("text")))
        return List(segments.length()) { i ->
            val s = segments.getJSONObject(i)
            TranscriptSegment(
                speaker = s.optString("speaker").ifBlank { null },
                startMs = (s.optDouble("start", 0.0) * 1000).toLong(),
                text = s.optString("text").trim(),
            )
        }
    }

    /** Whisper (Groq, OpenAI) не различает дикторов: только реплики с временем. */
    private fun whisperSegments(url: String, key: String, model: String, audio: File, mime: String): List<TranscriptSegment> {
        val response = Http.postMultipart(
            url = url,
            headers = mapOf("Authorization" to "Bearer $key"),
            fields = mapOf("model" to model, "language" to "he", "response_format" to "verbose_json"),
            fileField = "file",
            file = audio,
            fileMime = mime,
        )
        val json = JSONObject(response)
        val segments = json.optJSONArray("segments") ?: return listOf(TranscriptSegment(null, 0, json.optString("text")))
        return List(segments.length()) { i ->
            val s = segments.getJSONObject(i)
            TranscriptSegment(null, (s.optDouble("start", 0.0) * 1000).toLong(), s.optString("text").trim())
        }
    }

    // --- Soniox: загрузка файла → асинхронная транскрипция → опрос → результат → удаление ---

    private suspend fun soniox(key: String, model: String, audio: File, mime: String): String =
        sonioxTokens(key, model, audio, mime, diarize = false).joinToString("") { it.text }

    private suspend fun sonioxDiarized(key: String, model: String, audio: File, mime: String): List<TranscriptSegment> =
        groupWords(sonioxTokens(key, model, audio, mime, diarize = true))

    private suspend fun sonioxTokens(key: String, model: String, audio: File, mime: String, diarize: Boolean): List<Word> {
        val auth = mapOf("Authorization" to "Bearer $key")
        val fileId = JSONObject(
            Http.postMultipart("$SONIOX_BASE/files", auth, emptyMap(), "file", audio, mime),
        ).getString("id")
        var transcriptionId: String? = null
        try {
            val create = JSONObject()
                .put("model", model)
                .put("file_id", fileId)
                .put("language_hints", JSONArray().put("he"))
            if (diarize) create.put("enable_speaker_diarization", true)
            transcriptionId = JSONObject(Http.postJson("$SONIOX_BASE/transcriptions", auth, create.toString()))
                .getString("id")
            var polls = 0
            while (true) {
                if (++polls > SONIOX_MAX_POLLS) throw ApiException(0, "soniox timeout")
                val status = JSONObject(Http.get("$SONIOX_BASE/transcriptions/$transcriptionId", auth))
                when (status.optString("status")) {
                    "completed" -> break
                    "error" -> throw ApiException(500, status.optString("error_message", "soniox error"))
                }
                delay(1000)
            }
            val transcript = JSONObject(Http.get("$SONIOX_BASE/transcriptions/$transcriptionId/transcript", auth))
            val tokens = transcript.optJSONArray("tokens")
                ?: return listOf(Word(transcript.optString("text"), null, 0, spaced = false))
            // Токены Soniox уже содержат пробелы внутри text — склеиваются без разделителя.
            return List(tokens.length()) { i ->
                val t = tokens.getJSONObject(i)
                Word(
                    text = t.optString("text"),
                    speaker = if (t.has("speaker") && !t.isNull("speaker")) t.optString("speaker") else null,
                    startMs = t.optLong("start_ms"),
                    spaced = false,
                )
            }
        } finally {
            transcriptionId?.let { Http.delete("$SONIOX_BASE/transcriptions/$it", auth) }
            Http.delete("$SONIOX_BASE/files/$fileId", auth)
        }
    }

    private data class Word(val text: String, val speaker: String?, val startMs: Long, val spaced: Boolean)

    /** Слова/токены подряд от одного диктора → одна реплика. */
    private fun groupWords(words: List<Word>): List<TranscriptSegment> {
        val result = mutableListOf<TranscriptSegment>()
        var speaker: String? = null
        var start = 0L
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotBlank()) result += TranscriptSegment(speaker, start, sb.toString().trim())
            sb.clear()
        }
        for ((i, w) in words.withIndex()) {
            if (i == 0 || w.speaker != speaker) {
                flush()
                speaker = w.speaker
                start = w.startMs
            }
            if (w.spaced && sb.isNotEmpty()) sb.append(' ')
            sb.append(w.text)
        }
        flush()
        return result
    }
}
