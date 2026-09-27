package app.sicumi.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * Шаблон протокола: JSON-схема ответа + инструкция модели.
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
        private fun arr(items: JSONObject) = JSONObject().put("type", "array").put("items", items)
        private fun obj(vararg props: Pair<String, JSONObject>) = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
            .put("required", JSONArray().apply { props.forEach { put(it.first) } })
            .put("additionalProperties", false)

        val Default = ProtocolTemplate(
            id = "general",
            schema = {
                obj(
                    "title" to str(),
                    "date" to str(),
                    "participants" to arr(obj("name" to str(), "role" to str())),
                    "summary" to str(),
                    "topics" to arr(obj("title" to str(), "points" to arr(str()))),
                    "decisions" to arr(obj("text" to str(), "at" to str())),
                    "action_items" to arr(obj("task" to str(), "owner" to str(), "due" to str())),
                    "open_issues" to arr(str()),
                    "next_meeting" to str(),
                )
            },
            instructions = """
                You write meeting protocols (סיכום פגישה) in Hebrew for Israeli teams and businesses.
                You receive an automatic transcript of a recorded meeting inside <transcript> tags.
                Each line is "[mm:ss] דובר N: text". Speaker labels come from automatic diarization:
                they can be missing or wrong, and in long meetings the numbering may restart every ~20 minutes,
                so the same person can appear under different labels. Identify people by names and roles
                mentioned in the conversation when possible; never invent names.

                Write the protocol in clear, professional Hebrew (keep English product names and terms as spoken).
                Fill the JSON fields:
                - title: a short descriptive meeting title (3–7 words) based on the main subject.
                - date: the meeting date as dd.mm.yy from the provided metadata.
                - participants: people who took part or were addressed by name, with a role/company if mentioned
                  (role = "" if unknown). If no names are mentioned, return an empty list.
                - summary: 2–4 sentences — the purpose of the meeting and its main outcome.
                - topics: the subjects discussed, in the order they came up. Each has a short title and
                  concise bullet points with the essential facts, numbers, dates and positions. No filler.
                - decisions: what was agreed or decided. text = one clear sentence; at = the [mm:ss] time
                  of the transcript line where it was decided, written as mm:ss (or h:mm:ss), "" if unclear.
                - action_items: concrete follow-ups. owner = the responsible person or side ("" if not stated),
                  due = deadline as stated ("" if none). Start each task with a verb.
                - open_issues: questions left open or items waiting for information.
                - next_meeting: date/time or plan for the next meeting if mentioned, otherwise "".

                Rules:
                - Use only information from the transcript. Do not add assumptions or advice.
                - Correct obvious speech-recognition errors when the meaning is clear from context.
                - Give extra attention to the moments the user marked as important, if listed.
                - Skip small talk and off-topic chatter.
                - If the transcript is too short or empty of content, keep fields minimal rather than inventing.
            """.trimIndent(),
        )
    }
}
