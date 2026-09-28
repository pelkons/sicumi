package app.sicumi.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Какие модели нужны: распознавание речи или текстовая модель. */
enum class ModelKind { Stt, Llm }

/**
 * Список моделей берётся напрямую у провайдера (GET /models по ключу пользователя).
 * Sicumi не выбирает модели сам: здесь только отсекается то, что заведомо не подходит к функции
 * (например, модели картинок или озвучки в списке для протокола).
 */
object ModelCatalog {

    suspend fun list(provider: AiProvider, key: String, kind: ModelKind): List<String> = withContext(Dispatchers.IO) {
        val url = when (provider.id) {
            "gemini" -> provider.testUrl + "?pageSize=1000"
            "anthropic" -> provider.testUrl + "?limit=1000"
            else -> provider.testUrl
        }
        val json = JSONObject(Http.get(url, provider.authHeaders(key)))
        val models = parse(provider, json)
        models
            .filter { fits(provider, it, kind) }
            .map { it.id }
            .distinct()
            .let { ids ->
                // Soniox для файлов работает только с асинхронными моделями: если они есть, показываем только их.
                if (provider.id == "soniox" && ids.any { "async" in it }) ids.filter { "async" in it } else ids
            }
            .sortedWith(compareByDescending(NaturalOrder) { it })
    }

    private data class RemoteModel(val id: String, val methods: List<String>)

    private fun parse(provider: AiProvider, json: JSONObject): List<RemoteModel> {
        val array: JSONArray = json.optJSONArray("data") ?: json.optJSONArray("models") ?: JSONArray()
        return List(array.length()) { i -> array.optJSONObject(i) }.filterNotNull().mapNotNull { m ->
            val id = when (provider.id) {
                "gemini" -> m.optString("name").removePrefix("models/")
                else -> m.optString("id").ifBlank { m.optString("name") }
            }
            if (id.isBlank()) return@mapNotNull null
            val methodsJson = m.optJSONArray("supportedGenerationMethods")
            val methods = if (methodsJson == null) emptyList() else List(methodsJson.length()) { methodsJson.optString(it) }
            RemoteModel(id, methods)
        }
    }

    private fun fits(provider: AiProvider, m: RemoteModel, kind: ModelKind): Boolean {
        val id = m.id.lowercase()
        fun has(vararg parts: String) = parts.any { it in id }
        return when (kind) {
            ModelKind.Stt -> when (provider.id) {
                "gemini" -> has("transcribe") && !has("live")
                "openai" -> (has("transcribe") || id.startsWith("whisper")) && !has("realtime")
                "groq" -> has("whisper")
                // Soniox: для файлов нужен асинхронный режим; если в названиях его нет — показываем всё.
                "soniox" -> true
                else -> false
            }
            ModelKind.Llm -> when (provider.id) {
                "gemini" -> id.startsWith("gemini") &&
                    (m.methods.isEmpty() || "generateContent" in m.methods) &&
                    !has("embedding", "tts", "image", "live", "audio", "transcribe", "robotics", "computer-use")
                "openai" -> (id.startsWith("gpt-") || Regex("^o\\d").containsMatchIn(id) || id.startsWith("chat-")) &&
                    !has("transcribe", "tts", "audio", "realtime", "image", "search", "embedding", "moderation", "instruct", "diarize")
                "groq" -> !has("whisper", "guard", "tts", "playai", "orpheus")
                "anthropic" -> id.startsWith("claude")
                else -> false
            }
        }
    }

    /** Сортировка с учётом чисел: «model-10» после «model-9». Новые версии обычно оказываются выше. */
    private object NaturalOrder : Comparator<String> {
        private val chunk = Regex("\\d+|\\D+")
        override fun compare(a: String, b: String): Int {
            val x = chunk.findAll(a).map { it.value }.toList()
            val y = chunk.findAll(b).map { it.value }.toList()
            for (i in 0 until minOf(x.size, y.size)) {
                val p = x[i]
                val q = y[i]
                val c = if (p[0].isDigit() && q[0].isDigit()) {
                    p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 } ?: p.compareTo(q)
                } else {
                    p.compareTo(q)
                }
                if (c != 0) return c
            }
            return x.size.compareTo(y.size)
        }
    }
}
