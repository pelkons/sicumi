package app.sicumi.providers

import androidx.annotation.StringRes
import app.sicumi.R

enum class ProviderRole(@StringRes val label: Int) {
    Stt(R.string.role_stt),
    Llm(R.string.role_llm),
}

/**
 * Каталог провайдеров (BYOK). keyUrl — страница, где пользователь создаёт ключ.
 * testUrl + headers — лёгкий GET-запрос для проверки ключа (список моделей).
 */
data class AiProvider(
    val id: String,
    val name: String,
    val roles: List<ProviderRole>,
    val keyUrl: String,
    val testUrl: String,
    val authHeaders: (key: String) -> Map<String, String>,
)

/**
 * Назначение ключа. У каждого назначения свой выбранный провайдер и свой ключ,
 * даже если провайдер тот же (например, отдельный ключ Gemini для диктовки).
 */
enum class ApiPurpose(val id: String, @StringRes val title: Int, @StringRes val description: Int, val role: ProviderRole) {
    Transcription("transcription", R.string.purpose_transcription, R.string.purpose_transcription_desc, ProviderRole.Stt),
    Processing("processing", R.string.purpose_processing, R.string.purpose_processing_desc, ProviderRole.Llm),
    Dictation("dictation", R.string.purpose_dictation, R.string.purpose_dictation_desc, ProviderRole.Stt),
}

object AiProviders {
    private fun bearer(key: String) = mapOf("Authorization" to "Bearer $key")

    val all: List<AiProvider> = listOf(
        AiProvider(
            id = "gemini",
            name = "Gemini",
            roles = listOf(ProviderRole.Stt, ProviderRole.Llm),
            keyUrl = "https://aistudio.google.com/apikey",
            testUrl = "https://generativelanguage.googleapis.com/v1beta/models",
            authHeaders = { mapOf("x-goog-api-key" to it) },
        ),
        AiProvider(
            id = "soniox",
            name = "Soniox",
            roles = listOf(ProviderRole.Stt),
            keyUrl = "https://console.soniox.com/",
            testUrl = "https://api.soniox.com/v1/models",
            authHeaders = ::bearer,
        ),
        AiProvider(
            id = "openai",
            name = "OpenAI",
            roles = listOf(ProviderRole.Stt, ProviderRole.Llm),
            keyUrl = "https://platform.openai.com/api-keys",
            testUrl = "https://api.openai.com/v1/models",
            authHeaders = ::bearer,
        ),
        AiProvider(
            id = "groq",
            name = "Groq",
            roles = listOf(ProviderRole.Stt, ProviderRole.Llm),
            keyUrl = "https://console.groq.com/keys",
            testUrl = "https://api.groq.com/openai/v1/models",
            authHeaders = ::bearer,
        ),
        AiProvider(
            id = "anthropic",
            name = "Claude",
            roles = listOf(ProviderRole.Llm),
            keyUrl = "https://platform.claude.com/settings/keys",
            testUrl = "https://api.anthropic.com/v1/models",
            authHeaders = { mapOf("x-api-key" to it, "anthropic-version" to "2023-06-01") },
        ),
    )

    fun forPurpose(purpose: ApiPurpose): List<AiProvider> = all.filter { purpose.role in it.roles }
}
