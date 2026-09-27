package app.sicumi.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

enum class KeyCheck { Ok, Invalid, Error }

object KeyTester {
    /** GET на список моделей провайдера: 200 — ключ рабочий, 400/401/403 — ключ неверный. */
    suspend fun check(provider: AiProvider, key: String): KeyCheck = withContext(Dispatchers.IO) {
        val conn = (URL(provider.testUrl).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            provider.authHeaders(key).forEach { (name, value) -> conn.setRequestProperty(name, value) }
            when (conn.responseCode) {
                in 200..299 -> KeyCheck.Ok
                400, 401, 403 -> KeyCheck.Invalid
                else -> KeyCheck.Error
            }
        } catch (e: Exception) {
            KeyCheck.Error
        } finally {
            conn.disconnect()
        }
    }
}
