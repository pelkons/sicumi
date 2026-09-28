package app.sicumi.providers

import android.content.Context

/**
 * Что пользователь выбрал для каждого назначения: провайдера и модель этого провайдера.
 * Ключи хранятся в ApiKeyStore как "назначение:провайдер".
 * Модель не подставляется по умолчанию: пока пользователь её не выбрал, функция не запускается.
 */
class ApiSelection(context: Context) {
    private val prefs = context.getSharedPreferences("api_selection", Context.MODE_PRIVATE)

    fun selected(purpose: ApiPurpose): AiProvider {
        val options = AiProviders.forPurpose(purpose)
        val id = prefs.getString(purpose.id, null)
        return options.firstOrNull { it.id == id } ?: options.first()
    }

    fun select(purpose: ApiPurpose, provider: AiProvider) {
        prefs.edit().putString(purpose.id, provider.id).apply()
    }

    /** Модель для назначения у конкретного провайдера (у каждого провайдера — своя). null — не выбрана. */
    fun model(purpose: ApiPurpose, provider: AiProvider = selected(purpose)): String? =
        prefs.getString(modelKey(purpose, provider), null)

    fun setModel(purpose: ApiPurpose, provider: AiProvider, model: String?) {
        prefs.edit().apply { if (model == null) remove(modelKey(purpose, provider)) else putString(modelKey(purpose, provider), model) }.apply()
    }

    /**
     * Текстовая модель для очистки диктовки — отдельный платный запрос.
     * null — без очистки (так по умолчанию): вставляется распознанный текст как есть.
     */
    fun cleanupModel(provider: AiProvider): String? = prefs.getString(cleanupKey(provider), null)

    fun setCleanupModel(provider: AiProvider, model: String?) {
        prefs.edit().apply { if (model == null) remove(cleanupKey(provider)) else putString(cleanupKey(provider), model) }.apply()
    }

    private fun modelKey(purpose: ApiPurpose, provider: AiProvider) = "model:${purpose.id}:${provider.id}"
    private fun cleanupKey(provider: AiProvider) = "cleanup:${provider.id}"

    companion object {
        fun keyId(purpose: ApiPurpose, provider: AiProvider) = "${purpose.id}:${provider.id}"
    }
}
