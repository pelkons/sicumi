package app.sicumi.protocol

import org.json.JSONArray
import org.json.JSONObject

data class Participant(val name: String, val role: String)
data class Topic(val title: String, val points: List<String>)
data class Decision(val text: String, val at: String)
data class ActionItem(val task: String, val owner: String, val due: String, val done: Boolean = false)

/** Протокол встречи в том виде, в каком его видит и редактирует пользователь. */
data class Protocol(
    val title: String,
    val date: String,
    val participants: List<Participant>,
    val summary: String,
    val topics: List<Topic>,
    val decisions: List<Decision>,
    val actionItems: List<ActionItem>,
    val openIssues: List<String>,
    val nextMeeting: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("title", title)
        .put("date", date)
        .put("participants", JSONArray().apply { participants.forEach { put(JSONObject().put("name", it.name).put("role", it.role)) } })
        .put("summary", summary)
        .put(
            "topics",
            JSONArray().apply {
                topics.forEach { put(JSONObject().put("title", it.title).put("points", JSONArray(it.points))) }
            },
        )
        .put("decisions", JSONArray().apply { decisions.forEach { put(JSONObject().put("text", it.text).put("at", it.at)) } })
        .put(
            "action_items",
            JSONArray().apply {
                actionItems.forEach {
                    put(JSONObject().put("task", it.task).put("owner", it.owner).put("due", it.due).put("done", it.done))
                }
            },
        )
        .put("open_issues", JSONArray(openIssues))
        .put("next_meeting", nextMeeting)

    /** Текст для «Поделиться» (мессенджеры, почта). */
    fun toPlainText(labels: ProtocolLabels): String = buildString {
        append(title).append('\n')
        if (date.isNotBlank()) append(date).append('\n')
        if (participants.isNotEmpty()) {
            append('\n').append(labels.participants).append(": ")
            append(participants.joinToString(", ") { if (it.role.isBlank()) it.name else "${it.name} (${it.role})" })
            append('\n')
        }
        if (summary.isNotBlank()) append('\n').append(labels.summary).append('\n').append(summary).append('\n')
        topics.forEach { t ->
            append('\n').append(t.title).append('\n')
            t.points.forEach { append("• ").append(it).append('\n') }
        }
        if (decisions.isNotEmpty()) {
            append('\n').append(labels.decisions).append('\n')
            decisions.forEach { append("• ").append(it.text).append('\n') }
        }
        if (actionItems.isNotEmpty()) {
            append('\n').append(labels.tasks).append('\n')
            actionItems.forEach { a ->
                append(if (a.done) "☑ " else "☐ ").append(a.task)
                val meta = listOf(a.owner, a.due.takeIf { it.isNotBlank() }?.let { "${labels.until} $it" })
                    .filter { !it.isNullOrBlank() }
                if (meta.isNotEmpty()) append(" — ").append(meta.joinToString(", "))
                append('\n')
            }
        }
        if (openIssues.isNotEmpty()) {
            append('\n').append(labels.openIssues).append('\n')
            openIssues.forEach { append("• ").append(it).append('\n') }
        }
        if (nextMeeting.isNotBlank()) append('\n').append(labels.nextMeeting).append(": ").append(nextMeeting).append('\n')
    }.trim()

    companion object {
        fun fromJson(json: JSONObject): Protocol = Protocol(
            title = json.optString("title"),
            date = json.optString("date"),
            participants = json.optJSONArray("participants").objects().map {
                Participant(it.optString("name"), it.optString("role"))
            },
            summary = json.optString("summary"),
            topics = json.optJSONArray("topics").objects().map {
                Topic(it.optString("title"), it.optJSONArray("points").strings())
            },
            // Совместимость: решения могли прийти строками.
            decisions = json.optJSONArray("decisions").let { arr ->
                if (arr == null) emptyList() else List(arr.length()) { i ->
                    when (val v = arr.get(i)) {
                        is JSONObject -> Decision(v.optString("text"), v.optString("at"))
                        else -> Decision(v.toString(), "")
                    }
                }
            },
            actionItems = json.optJSONArray("action_items").objects().map {
                ActionItem(it.optString("task"), it.optString("owner"), it.optString("due"), it.optBoolean("done", false))
            },
            openIssues = json.optJSONArray("open_issues").strings(),
            nextMeeting = json.optString("next_meeting"),
        )

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else List(length()) { optJSONObject(it) ?: JSONObject() }

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else List(length()) { optString(it) }.filter { it.isNotBlank() }
    }
}

/** Подписи разделов (строки из ресурсов), чтобы модель данных не зависела от Context. */
data class ProtocolLabels(
    val participants: String,
    val summary: String,
    val decisions: String,
    val tasks: String,
    val openIssues: String,
    val nextMeeting: String,
    val until: String,
)
