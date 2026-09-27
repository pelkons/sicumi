package app.sicumi.providers

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Ошибка HTTP от провайдера. code == 0 — сеть недоступна или таймаут. */
class ApiException(val code: Int, message: String) : IOException(message) {
    val isAuth: Boolean get() = code == 401 || code == 403
}

/**
 * Минимальный HTTP-клиент на HttpURLConnection, без сторонних зависимостей.
 * Все методы блокирующие: вызывать только на Dispatchers.IO.
 */
object Http {

    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 300_000

    fun get(url: String, headers: Map<String, String>): String =
        request("GET", url, headers, contentType = null, body = null)

    fun delete(url: String, headers: Map<String, String>) {
        try {
            request("DELETE", url, headers, contentType = null, body = null)
        } catch (_: IOException) {
            // Очистка на стороне провайдера — best effort.
        }
    }

    fun postJson(url: String, headers: Map<String, String>, json: String): String =
        request("POST", url, headers, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))

    /** multipart/form-data: текстовые поля + один файл. */
    fun postMultipart(
        url: String,
        headers: Map<String, String>,
        fields: Map<String, String>,
        fileField: String,
        file: File,
        fileMime: String,
    ): String {
        val boundary = "sicumi-" + UUID.randomUUID().toString()
        val out = ByteArrayOutputStream()
        fun line(s: String) = out.write((s + "\r\n").toByteArray(Charsets.UTF_8))
        fields.forEach { (name, value) ->
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"$name\"")
            line("")
            line(value)
        }
        line("--$boundary")
        line("Content-Disposition: form-data; name=\"$fileField\"; filename=\"${file.name}\"")
        line("Content-Type: $fileMime")
        line("")
        file.inputStream().use { it.copyTo(out) }
        line("")
        line("--$boundary--")
        return request("POST", url, headers, "multipart/form-data; boundary=$boundary", out.toByteArray())
    }

    private fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        contentType: String?,
        body: ByteArray?,
    ): String {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw ApiException(0, e.message ?: "connection")
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                contentType?.let { conn.setRequestProperty("Content-Type", it) }
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream).readText()
            if (code !in 200..299) throw ApiException(code, text.take(500))
            return text
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw ApiException(0, e.message ?: "network")
        } finally {
            conn.disconnect()
        }
    }

    private fun InputStream?.readText(): String =
        this?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
}
