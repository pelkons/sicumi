package app.sicumi.providers

import android.content.Context

/** Какой провайдер выбран для каждого назначения. Ключи хранятся в ApiKeyStore как "назначение:провайдер". */
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

    companion object {
        fun keyId(purpose: ApiPurpose, provider: AiProvider) = "${purpose.id}:${provider.id}"
    }
}
