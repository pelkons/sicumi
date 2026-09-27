package app.sicumi.providers

import org.json.JSONArray
import org.json.JSONObject

/** Реплика транскрипта. speaker — метка провайдера (может отсутствовать), startMs — от начала встречи. */
data class TranscriptSegment(val speaker: String?, val startMs: Long, val text: String) {
    fun toJson(): JSONObject = JSONObject()
        .put("speaker", speaker ?: JSONObject.NULL)
        .put("startMs", startMs)
        .put("text", text)

    companion object {
        fun fromJson(json: JSONObject) = TranscriptSegment(
            speaker = if (json.isNull("speaker")) null else json.optString("speaker"),
            startMs = json.optLong("startMs"),
            text = json.optString("text"),
        )

        fun listToJson(list: List<TranscriptSegment>): JSONObject =
            JSONObject().put("segments", JSONArray().apply { list.forEach { put(it.toJson()) } })

        fun listFromJson(json: JSONObject?): List<TranscriptSegment> {
            val arr = json?.optJSONArray("segments") ?: return emptyList()
            return List(arr.length()) { fromJson(arr.getJSONObject(it)) }
        }
    }
}
