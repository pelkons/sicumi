package app.sicumi.protocol

import org.json.JSONArray
import org.json.JSONObject

/** Участник: дисциплина/роль, имена, компания, присутствие («נכח», «לא נכח», «לא נדרש» или ""). */
data class Participant(val role: String, val name: String, val company: String, val attendance: String)

/** Замечание в строке таблицы. important — выделяется (срочно, повторное замечание, обязательство). */
data class Note(val text: String, val important: Boolean)

/** Строка таблицы протокола: נושא / הערות / אחריות / תאריך יעד. due — «22/03», «שוטף», «לידיעה» или "". */
data class ProtocolItem(val topic: String, val notes: List<Note>, val owner: String, val due: String)

data class Decision(val text: String, val at: String)
data class ActionItem(val task: String, val owner: String, val due: String, val done: Boolean = false)

/**
 * Протокол встречи в формате израильского «סיכום פגישה»: шапка (дата, הנדון, נוכחים),
 * таблица тем с ответственными и сроками, рассылка (העתק). Плюс краткое резюме,
 * решения и чек-лист задач — для экрана приложения.
 */
data class Protocol(
    val title: String,
    val subject: String,
    val date: String,
    val participants: List<Participant>,
    val summary: String,
    val items: List<ProtocolItem>,
    val decisions: List<Decision>,
    val actionItems: List<ActionItem>,
    val nextMeeting: String,
    val cc: List<String>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("title", title)
        .put("subject", subject)
        .put("date", date)
        .put(
            "participants",
            JSONArray().apply {
                participants.forEach {
                    put(
                        JSONObject().put("role", it.role).put("name", it.name)
                            .put("company", it.company).put("attendance", it.attendance),
                    )
                }
            },
        )
        .put("summary", summary)
        .put(
            "items",
            JSONArray().apply {
                items.forEach { item ->
                    put(
                        JSONObject()
                            .put("topic", item.topic)
                            .put(
                                "notes",
                                JSONArray().apply {
                                    item.notes.forEach { put(JSONObject().put("text", it.text).put("important", it.important)) }
                                },
                            )
                            .put("owner", item.owner)
                            .put("due", item.due),
                    )
                }
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
        .put("next_meeting", nextMeeting)
        .put("cc", JSONArray(cc))

    /** Текст для «Поделиться» (мессенджеры, почта). */
    fun toPlainText(l: ProtocolLabels): String = buildString {
        if (date.isNotBlank()) append(date).append('\n')
        append(l.subject).append(": ").append(subject.ifBlank { title }).append('\n')
        if (participants.isNotEmpty()) {
            append('\n').append(l.participants).append(":\n")
            participants.forEach { append(participantLine(it)).append('\n') }
        }
        if (summary.isNotBlank()) append('\n').append(l.summary).append(":\n").append(summary).append('\n')
        items.forEachIndexed { i, item ->
            append('\n').append(i + 1).append(". ").append(item.topic).append('\n')
            item.notes.forEach { n -> append(if (n.important) "• (!) " else "• ").append(n.text).append('\n') }
            val meta = listOf(
                item.owner.takeIf { it.isNotBlank() }?.let { "${l.owner}: $it" },
                item.due.takeIf { it.isNotBlank() }?.let { "${l.due}: $it" },
            ).filterNotNull()
            if (meta.isNotEmpty()) append(meta.joinToString(" | ")).append('\n')
        }
        if (decisions.isNotEmpty()) {
            append('\n').append(l.decisions).append(":\n")
            decisions.forEach { append("• ").append(it.text).append('\n') }
        }
        if (actionItems.isNotEmpty()) {
            append('\n').append(l.tasks).append(":\n")
            actionItems.forEach { a ->
                append(if (a.done) "☑ " else "☐ ").append(a.task)
                val meta = listOf(a.owner, a.due.takeIf { it.isNotBlank() }?.let { "${l.until} $it" })
                    .filter { !it.isNullOrBlank() }
                if (meta.isNotEmpty()) append(" — ").append(meta.joinToString(", "))
                append('\n')
            }
        }
        if (nextMeeting.isNotBlank()) append('\n').append(l.nextMeeting).append(": ").append(nextMeeting).append('\n')
        if (cc.isNotEmpty()) append('\n').append(l.cc).append(": ").append(cc.joinToString(", ")).append('\n')
    }.trim()

    companion object {
        /** «תנועה: הדס – דגש הנדסה – נכחה» */
        fun participantLine(p: Participant): String = buildString {
            if (p.role.isNotBlank()) append(p.role).append(": ")
            append(listOf(p.name, p.company, p.attendance).filter { it.isNotBlank() }.joinToString(" – "))
        }

        fun fromJson(json: JSONObject): Protocol = Protocol(
            title = json.optString("title"),
            subject = json.optString("subject"),
            date = json.optString("date"),
            participants = json.optJSONArray("participants").objects().map {
                Participant(it.optString("role"), it.optString("name"), it.optString("company"), it.optString("attendance"))
            },
            summary = json.optString("summary"),
            items = json.optJSONArray("items").objects().map { o ->
                ProtocolItem(
                    topic = o.optString("topic"),
                    notes = o.optJSONArray("notes").let { arr ->
                        if (arr == null) emptyList() else List(arr.length()) { i ->
                            when (val v = arr.get(i)) {
                                is JSONObject -> Note(v.optString("text"), v.optBoolean("important", false))
                                else -> Note(v.toString(), false)
                            }
                        }.filter { it.text.isNotBlank() }
                    },
                    owner = o.optString("owner"),
                    due = o.optString("due"),
                )
            },
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
            nextMeeting = json.optString("next_meeting"),
            cc = json.optJSONArray("cc").strings(),
        )

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else List(length()) { optJSONObject(it) ?: JSONObject() }

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else List(length()) { optString(it) }.filter { it.isNotBlank() }
    }
}

/** Подписи разделов (строки из ресурсов), чтобы модель данных не зависела от Context. */
data class ProtocolLabels(
    val subject: String,
    val participants: String,
    val summary: String,
    val number: String,
    val topic: String,
    val notes: String,
    val owner: String,
    val due: String,
    val decisions: String,
    val tasks: String,
    val nextMeeting: String,
    val cc: String,
    val until: String,
)
