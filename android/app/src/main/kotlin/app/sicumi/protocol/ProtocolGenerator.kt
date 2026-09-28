package app.sicumi.protocol

import app.sicumi.meetings.Meeting
import app.sicumi.meetings.MeetingTitles
import app.sicumi.providers.AiProvider
import app.sicumi.providers.LlmClient
import app.sicumi.providers.LlmTier
import app.sicumi.providers.TranscriptSegment
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Транскрипт → протокол (JSON по схеме шаблона) моделью, выбранной для «עיבוד התמלול לפרוטוקול». */
object ProtocolGenerator {

    suspend fun generate(
        provider: AiProvider,
        key: String,
        meeting: Meeting,
        transcript: List<TranscriptSegment>,
        template: ProtocolTemplate = ProtocolTemplate.Default,
    ): JSONObject {
        val user = buildString {
            append("Meeting date: ")
                .append(SimpleDateFormat("dd/MM/yyyy (EEEE), HH:mm", Locale.ENGLISH).format(Date(meeting.createdAt)))
                .append('\n')
            append("Recording length: ").append(MeetingTitles.duration(meeting.durationMs)).append('\n')
            if (meeting.bookmarks.isNotEmpty()) {
                append("Moments the user marked as important during the meeting: ")
                append(meeting.bookmarks.joinToString(", ") { "[${MeetingTitles.duration(it)}]" })
                append('\n')
            }
            append("\n<transcript>\n")
            append(formatTranscript(transcript))
            append("</transcript>")
        }
        val raw = LlmClient.complete(
            provider = provider,
            key = key,
            tier = LlmTier.Smart,
            system = template.instructions,
            user = user,
            jsonSchema = template.schema(),
            schemaName = "meeting_protocol",
        )
        return parseJson(raw)
    }

    /** [mm:ss] דובר 2: טקסט — метки дикторов заменяются на нейтральные «דובר N». */
    fun formatTranscript(transcript: List<TranscriptSegment>): String {
        val names = LinkedHashMap<String, Int>()
        return buildString {
            transcript.forEach { s ->
                append('[').append(MeetingTitles.duration(s.startMs)).append("] ")
                s.speaker?.let { sp ->
                    val n = names.getOrPut(sp) { names.size + 1 }
                    append("דובר ").append(n).append(": ")
                }
                append(s.text.trim()).append('\n')
            }
        }
    }

    /** Модели иногда оборачивают JSON в ```json … ``` — снимаем обёртку. */
    private fun parseJson(raw: String): JSONObject {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        require(start >= 0 && end > start) { "no json in model output" }
        return JSONObject(text.substring(start, end + 1))
    }
}
