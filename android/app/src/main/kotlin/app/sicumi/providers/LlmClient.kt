package app.sicumi.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fast — короткие задачи (очистка диктовки): дешёвая быстрая модель без размышлений.
 * Smart — протокол встречи: сильная модель.
 */
enum class LlmTier { Fast, Smart }

/** Вызов текстовой модели выбранного провайдера. jsonSchema != null — ответ строго по JSON-схеме. */
object LlmClient {

    private const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta"

    fun model(provider: AiProvider, tier: LlmTier): String = when (provider.id) {
        "gemini" -> if (tier == LlmTier.Smart) "gemini-3.8-flash" else "gemini-3.5-flash-lite"
        "openai" -> if (tier == LlmTier.Smart) "gpt-6-sol" else "gpt-6-luna"
        "groq" -> if (tier == LlmTier.Smart) "openai/gpt-oss-120b" else "openai/gpt-oss-20b"
        "anthropic" -> if (tier == LlmTier.Smart) "claude-sonnet-5" else "claude-haiku-4-5"
        else -> throw IllegalArgumentException("LLM is not supported by ${provider.id}")
    }

    suspend fun complete(
        provider: AiProvider,
        key: String,
        tier: LlmTier,
        system: String,
        user: String,
        jsonSchema: JSONObject? = null,
        schemaName: String = "result",
    ): String = withContext(Dispatchers.IO) {
        val model = model(provider, tier)
        val text = when (provider.id) {
            "gemini" -> gemini(key, model, tier, system, user, jsonSchema)
            "openai" -> openAi(key, model, tier, system, user, jsonSchema, schemaName)
            "groq" -> groq(key, model, tier, system, user, jsonSchema, schemaName)
            "anthropic" -> claude(key, model, tier, system, user, jsonSchema)
            else -> throw IllegalArgumentException("LLM is not supported by ${provider.id}")
        }
        text.trim()
    }

    // --- Gemini: generateContent ---

    private fun gemini(key: String, model: String, tier: LlmTier, system: String, user: String, schema: JSONObject?): String {
        val config = JSONObject()
        if (tier == LlmTier.Smart) config.put("thinkingConfig", JSONObject().put("thinkingLevel", "medium"))
        if (schema != null) {
            config.put("responseMimeType", "application/json")
            config.put("responseJsonSchema", schema)
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user))),
                ),
            )
            .put("generationConfig", config)
        val json = JSONObject(
            Http.postJson("$GEMINI_BASE/models/$model:generateContent", mapOf("x-goog-api-key" to key), body.toString()),
        )
        val parts = json.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optBoolean("thought", false)) continue
            sb.append(part.optString("text"))
        }
        return sb.toString()
    }

    // --- OpenAI: Responses API ---

    private fun openAi(
        key: String, model: String, tier: LlmTier, system: String, user: String,
        schema: JSONObject?, schemaName: String,
    ): String {
        val body = JSONObject()
            .put("model", model)
            .put("instructions", system)
            .put("input", user)
            .put("store", false)
        if (tier == LlmTier.Fast) body.put("reasoning", JSONObject().put("effort", "none"))
        if (schema != null) {
            body.put(
                "text",
                JSONObject().put(
                    "format",
                    JSONObject().put("type", "json_schema").put("name", schemaName).put("strict", true).put("schema", schema),
                ),
            )
        }
        val json = JSONObject(
            Http.postJson("https://api.openai.com/v1/responses", mapOf("Authorization" to "Bearer $key"), body.toString()),
        )
        val output = json.optJSONArray("output") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") == "output_text") sb.append(part.optString("text"))
            }
        }
        return sb.toString()
    }

    // --- Groq: Chat Completions (OpenAI-совместимый) ---

    private fun groq(
        key: String, model: String, tier: LlmTier, system: String, user: String,
        schema: JSONObject?, schemaName: String,
    ): String {
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
            .put("reasoning_effort", if (tier == LlmTier.Fast) "low" else "medium")
            .put("include_reasoning", false)
        if (schema != null) {
            body.put(
                "response_format",
                JSONObject().put("type", "json_schema").put(
                    "json_schema",
                    JSONObject().put("name", schemaName).put("strict", true).put("schema", schema),
                ),
            )
        }
        val json = JSONObject(
            Http.postJson(
                "https://api.groq.com/openai/v1/chat/completions",
                mapOf("Authorization" to "Bearer $key"),
                body.toString(),
            ),
        )
        return json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
    }

    // --- Claude: Messages API ---

    private fun claude(key: String, model: String, tier: LlmTier, system: String, user: String, schema: JSONObject?): String {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", if (tier == LlmTier.Smart) 16000 else 4096)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        if (schema != null) {
            body.put("output_config", JSONObject().put("format", JSONObject().put("type", "json_schema").put("schema", schema)))
        }
        val json = JSONObject(
            Http.postJson(
                "https://api.anthropic.com/v1/messages",
                mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"),
                body.toString(),
            ),
        )
        val content = json.optJSONArray("content") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            if (part.optString("type") == "text") sb.append(part.optString("text"))
        }
        return sb.toString()
    }
}
