package app.sicumi.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Как настроить рассуждения модели: Fast — короткие задачи (очистка диктовки) без размышлений,
 * Smart — протокол встречи. Саму модель выбирает пользователь в настройках.
 */
enum class LlmTier { Fast, Smart }

/** Вызов текстовой модели выбранного провайдера. jsonSchema != null — ответ строго по JSON-схеме. */
object LlmClient {

    private const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta"

    suspend fun complete(
        provider: AiProvider,
        key: String,
        model: String,
        tier: LlmTier,
        system: String,
        user: String,
        jsonSchema: JSONObject? = null,
        schemaName: String = "result",
    ): String = withContext(Dispatchers.IO) {
        fun call(reasoning: Boolean) = when (provider.id) {
            "gemini" -> gemini(key, model, tier, reasoning, system, user, jsonSchema)
            "openai" -> openAi(key, model, tier, reasoning, system, user, jsonSchema, schemaName)
            "groq" -> groq(key, model, tier, reasoning, system, user, jsonSchema, schemaName)
            "anthropic" -> claude(key, model, tier, system, user, jsonSchema)
            else -> throw IllegalArgumentException("LLM is not supported by ${provider.id}")
        }
        // Модель выбирает пользователь, и не каждая понимает параметры рассуждений.
        // Если провайдер отклонил запрос (400), повторяем один раз без них.
        val text = try {
            call(reasoning = true)
        } catch (e: ApiException) {
            if (e.code == 400 && provider.id != "anthropic") call(reasoning = false) else throw e
        }
        text.trim()
    }

    // --- Gemini: generateContent ---

    private fun gemini(
        key: String, model: String, tier: LlmTier, reasoning: Boolean, system: String, user: String, schema: JSONObject?,
    ): String {
        val config = JSONObject()
        if (reasoning && tier == LlmTier.Smart) config.put("thinkingConfig", JSONObject().put("thinkingLevel", "medium"))
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
        key: String, model: String, tier: LlmTier, reasoning: Boolean, system: String, user: String,
        schema: JSONObject?, schemaName: String,
    ): String {
        val body = JSONObject()
            .put("model", model)
            .put("instructions", system)
            .put("input", user)
            .put("store", false)
        if (reasoning && tier == LlmTier.Fast) body.put("reasoning", JSONObject().put("effort", "none"))
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
        key: String, model: String, tier: LlmTier, reasoning: Boolean, system: String, user: String,
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
        if (reasoning) {
            body.put("reasoning_effort", if (tier == LlmTier.Fast) "low" else "medium")
            body.put("include_reasoning", false)
        }
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
