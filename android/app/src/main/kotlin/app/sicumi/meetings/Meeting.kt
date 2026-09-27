package app.sicumi.meetings

import org.json.JSONArray
import org.json.JSONObject

/** Этапы жизни встречи. Порядок важен: всё между Recorded и Ready — обработка. */
enum class MeetingStatus {
    Recording,
    Recorded,
    Preparing,
    Transcribing,
    Summarizing,
    Ready,
    Failed;

    val isProcessing: Boolean get() = this in listOf(Recorded, Preparing, Transcribing, Summarizing)
}

enum class MeetingSource { Recorded, Imported }

/** Кусок аудио встречи. startMs — смещение от начала записи (паузы не считаются). */
data class AudioSegment(val file: String, val startMs: Long, val mime: String)

data class Meeting(
    val id: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val source: MeetingSource,
    val status: MeetingStatus,
    val segments: List<AudioSegment>,
    /** Отмеченные «важные моменты», мс от начала записи. */
    val bookmarks: List<Long>,
    /** Статус, на котором обработка остановилась с ошибкой (для повтора). */
    val failedAt: MeetingStatus? = null,
    val error: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("createdAt", createdAt)
        .put("durationMs", durationMs)
        .put("source", source.name)
        .put("status", status.name)
        .put(
            "segments",
            JSONArray().apply {
                segments.forEach {
                    put(JSONObject().put("file", it.file).put("startMs", it.startMs).put("mime", it.mime))
                }
            },
        )
        .put("bookmarks", JSONArray().apply { bookmarks.forEach { put(it) } })
        .put("failedAt", failedAt?.name ?: JSONObject.NULL)
        .put("error", error ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject): Meeting {
            val segments = json.optJSONArray("segments") ?: JSONArray()
            val bookmarks = json.optJSONArray("bookmarks") ?: JSONArray()
            return Meeting(
                id = json.getString("id"),
                title = json.optString("title"),
                createdAt = json.optLong("createdAt"),
                durationMs = json.optLong("durationMs"),
                source = enumOr(json.optString("source"), MeetingSource.Recorded),
                status = enumOr(json.optString("status"), MeetingStatus.Failed),
                segments = List(segments.length()) { i ->
                    val s = segments.getJSONObject(i)
                    AudioSegment(s.getString("file"), s.optLong("startMs"), s.optString("mime", "audio/m4a"))
                },
                bookmarks = List(bookmarks.length()) { bookmarks.getLong(it) },
                failedAt = json.optString("failedAt").takeIf { json.has("failedAt") && !json.isNull("failedAt") }
                    ?.let { enumOr<MeetingStatus>(it, MeetingStatus.Transcribing) },
                error = if (json.isNull("error")) null else json.optString("error"),
            )
        }

        private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T =
            enumValues<T>().firstOrNull { it.name == name } ?: fallback
    }
}
