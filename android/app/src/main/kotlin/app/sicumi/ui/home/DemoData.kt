package app.sicumi.ui.home

/**
 * Временные демо-данные для главного экрана, пока нет слоя данных (Room).
 * Удалить, когда появится настоящий список встреч.
 */
data class MeetingUi(
    val id: String,
    val title: String,
    val meta: String,
    /** null — протокол готов; 0..1 — идёт обработка. */
    val progress: Float?,
)

internal val DemoMeetings = listOf(
    MeetingUi("1", "ישיבת צוות שבועית", "יום א׳, 28/09 · 42 דק׳ · 5 משימות", null),
    MeetingUi("2", "פגישה עם הקבלן", "יום ה׳, 25/09 · 1:15 שע׳", 0.62f),
    MeetingUi("3", "סקירת תכנון", "יום ד׳, 24/09 · 28 דק׳ · 4 משימות", null),
)
