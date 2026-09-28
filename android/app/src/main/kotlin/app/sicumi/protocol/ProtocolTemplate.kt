package app.sicumi.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * Шаблон протокола: JSON-схема ответа + инструкция модели.
 * Формат по умолчанию повторяет принятый в израильских компаниях «סיכום פגישה»:
 * дата, הנדון, נוכחים (дисциплина – имя – компания – присутствие), таблица
 * מס"ד / נושא / הערות / אחריות / תאריך יעד, выделение важных замечаний, העתק.
 *
 * Схема совместима со строгим режимом OpenAI/Groq (все поля обязательны, additionalProperties=false)
 * и с responseJsonSchema Gemini / output_config Claude. Пустые значения — "" и [], не null.
 */
data class ProtocolTemplate(
    val id: String,
    val instructions: String,
    val schema: () -> JSONObject,
) {
    companion object {

        private fun str() = JSONObject().put("type", "string")
        private fun bool() = JSONObject().put("type", "boolean")
        private fun arr(items: JSONObject) = JSONObject().put("type", "array").put("items", items)
        private fun obj(vararg props: Pair<String, JSONObject>) = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
            .put("required", JSONArray().apply { props.forEach { put(it.first) } })
            .put("additionalProperties", false)

        val Default = ProtocolTemplate(
            id = "meeting-summary",
            schema = {
                obj(
                    "title" to str(),
                    "subject" to str(),
                    "date" to str(),
                    "participants" to arr(
                        obj("role" to str(), "name" to str(), "company" to str(), "attendance" to str()),
                    ),
                    "summary" to str(),
                    "items" to arr(
                        obj(
                            "topic" to str(),
                            "notes" to arr(obj("text" to str(), "important" to bool())),
                            "owner" to str(),
                            "due" to str(),
                        ),
                    ),
                    "decisions" to arr(obj("text" to str(), "at" to str())),
                    "action_items" to arr(obj("task" to str(), "owner" to str(), "due" to str())),
                    "next_meeting" to str(),
                    "cc" to arr(str()),
                )
            },
            instructions = """
                You write meeting protocols (סיכום פגישה) in Hebrew, in the format used by Israeli companies
                for work and coordination meetings (for example a developer with architects and engineering
                consultants, or any team meeting).

                You receive an automatic transcript of a recorded meeting inside <transcript> tags.
                Each line is "[mm:ss] דובר N: text". Speaker labels come from automatic diarization:
                they can be missing or wrong, and in long meetings the numbering may restart every ~20 minutes,
                so the same person can appear under different labels. Identify people by the names, roles and
                companies mentioned in the conversation; never invent names.

                Write in concise professional Hebrew, in the telegraphic style of a written protocol
                ("נדרש לבחון…", "סוכם ש…", "יש לתאם…"). Keep English terms, company names, building letters and
                numbers as spoken. Use common professional abbreviations when the speakers use them
                (for example קונס', אינס', מ"א, יח').

                Fill the JSON fields:
                - title: a short title for the app list (3–7 words), e.g. the project name and meeting type.
                - subject: the "הנדון" line: "<project or client> – סיכום פגישה <meeting type>". Do not repeat the date.
                - date: the meeting date as dd/mm/yyyy from the provided metadata.
                - participants: everyone who attended or was mentioned as expected to attend, grouped as in a
                  protocol header. role = discipline or role (אדריכלות, קונסטרוקציה, חשמל, יזם, מנהל פרויקט…),
                  name = one or more names separated by "/", company = company or office, attendance = "נכח"/"נכחה"
                  for people who spoke or were addressed as present, "לא נכח"/"לא נכחה" if explicitly absent,
                  "לא נדרש" if said not to be required, otherwise "". Empty strings for unknown parts.
                - summary: 2–3 sentences with the purpose of the meeting and its main outcomes (for the app screen).
                - items: the protocol table. One row per topic or discipline, in the order discussed.
                  topic = short subject or discipline name (e.g. "חשמל", "תנועה", "רישוי", "לו"ז").
                  notes = the substance of the discussion as short separate statements: requirements, findings,
                  agreements and who must do what. important = true for notes that must stand out: urgent items,
                  repeated or overdue remarks ("הערה חוזרת"), commitments with a near deadline, blockers.
                  owner = the responsible side(s), roles or names separated by "/" ("" if not stated).
                  due = target date as dd/mm, or "שוטף" for ongoing work, or "לידיעה" for information only,
                  or "" if nothing fits.
                - decisions: explicit agreements and decisions ("סוכם", "הוחלט", "אושר"). text = one sentence;
                  at = the [mm:ss] time of the transcript line where it was decided, written as mm:ss or h:mm:ss,
                  "" if unclear.
                - action_items: concrete follow-up tasks, each starting with a verb, with owner and due
                  (same formats as in items). These may repeat the task notes of the table as a checklist.
                - next_meeting: date, time and participants of a follow-up meeting if one was set, otherwise "".
                - cc: people or groups the protocol should be sent to if mentioned (העתק), otherwise [].

                Rules:
                - Use only information from the transcript. Do not add assumptions, advice or generic statements.
                - Correct obvious speech-recognition errors when the meaning is clear from context.
                - Resolve relative dates ("מחר", "עד יום א'") to dd/mm using the meeting date when possible.
                - Give extra attention to the moments the user marked as important, if listed.
                - Skip small talk, greetings and off-topic chatter.
                - If the transcript is too short or has no real content, keep fields minimal rather than inventing.
            """.trimIndent(),
        )
    }
}
