package app.sicumi.pipeline

import android.content.Context
import app.sicumi.meetings.AudioSegment
import app.sicumi.meetings.Meeting
import app.sicumi.meetings.MeetingRepository
import app.sicumi.meetings.MeetingSource
import app.sicumi.meetings.MeetingStatus
import app.sicumi.protocol.ProtocolGenerator
import app.sicumi.providers.AiProvider
import app.sicumi.providers.ApiException
import app.sicumi.providers.ApiKeyStore
import app.sicumi.providers.ApiPurpose
import app.sicumi.providers.ApiSelection
import app.sicumi.providers.SttClient
import app.sicumi.providers.TranscriptSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.coroutineContext

/** Ошибка обработки с машинным кодом; текст для пользователя строит UI (см. MeetingErrors). */
class PipelineException(val code: String) : Exception(code)

/**
 * Конвейер: (импорт → перекодирование) → транскрипция по сегментам → протокол.
 * Каждый шаг сохраняет результат на диск, поэтому повтор после ошибки продолжает с места сбоя:
 * уже распознанные сегменты повторно не отправляются.
 */
object MeetingProcessor {

    private const val SEGMENT_MS = 20 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Прогресс текущего шага 0..1 по id встречи. */
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    private val _active = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _active.asStateFlow()

    fun enqueue(context: Context, meetingId: String) {
        val app = context.applicationContext
        synchronized(jobs) {
            if (jobs[meetingId]?.isActive == true) return
            val job = scope.launch { run(app, meetingId) }
            jobs[meetingId] = job
            _active.value = jobs.values.count { it.isActive }
            job.invokeOnCompletion {
                synchronized(jobs) {
                    jobs.remove(meetingId)
                    _active.value = jobs.values.count { it.isActive }
                }
                setProgress(meetingId, null)
            }
        }
        ProcessingService.ensureRunning(app)
    }

    /** Встречи, прерванные закрытием процесса, продолжают обработку при следующем запуске. */
    fun resumePending(context: Context) {
        MeetingRepository.get(context).meetings.value
            .filter { it.status.isProcessing }
            .forEach { enqueue(context, it.id) }
    }

    fun cancel(meetingId: String) {
        synchronized(jobs) { jobs[meetingId]?.cancel() }
    }

    private suspend fun run(context: Context, id: String) {
        val repo = MeetingRepository.get(context)
        try {
            var meeting = repo.get(id) ?: return
            if (needsPreparing(meeting)) meeting = prepare(context, meeting)
            val transcript = repo.readJson(id, MeetingRepository.TRANSCRIPT)
                ?.let { TranscriptSegment.listFromJson(it) }
                ?.takeIf { it.isNotEmpty() }
                ?: transcribe(context, meeting)
            summarize(context, repo.get(id) ?: return, transcript)
            repo.update(id) { it.copy(status = MeetingStatus.Ready, failedAt = null, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val code = when (e) {
                is PipelineException -> e.code
                is ApiException -> when {
                    e.code == 0 -> "network"
                    e.isAuth -> "auth"
                    e.code == 429 -> "rate"
                    e.code == 413 -> "too_large"
                    else -> "http:${e.code}"
                }
                else -> "other:${e.javaClass.simpleName}"
            }
            repo.update(id) {
                val stage = if (it.status == MeetingStatus.Failed) it.failedAt else it.status
                it.copy(status = MeetingStatus.Failed, failedAt = stage, error = code)
            }
        }
    }

    // --- Подготовка импортированного файла ---

    private fun needsPreparing(m: Meeting) =
        m.source == MeetingSource.Imported && m.segments.any { it.file.startsWith("source.") }

    private suspend fun prepare(context: Context, meeting: Meeting): Meeting {
        val repo = MeetingRepository.get(context)
        repo.update(meeting.id) { it.copy(status = MeetingStatus.Preparing) }
        val dir = repo.dir(meeting.id)
        val source = File(dir, meeting.segments.first().file)
        val ctx = coroutineContext
        val files = try {
            AudioTranscoder(
                segmentMs = SEGMENT_MS,
                isCancelled = { !ctx.isActive },
                onProgress = { setProgress(meeting.id, it) },
            ).transcode(source, dir) { "part-%03d.m4a".format(it) }
        } catch (e: InterruptedException) {
            throw CancellationException("cancelled")
        } catch (e: Exception) {
            throw PipelineException("audio")
        }
        if (files.isEmpty()) throw PipelineException("audio")
        source.delete()
        val segments = files.mapIndexed { i, f -> AudioSegment(f.name, i * SEGMENT_MS, "audio/m4a") }
        return repo.update(meeting.id) { it.copy(segments = segments) } ?: meeting
    }

    // --- Транскрипция ---

    private suspend fun transcribe(context: Context, meeting: Meeting): List<TranscriptSegment> {
        val repo = MeetingRepository.get(context)
        val (provider, key) = credentials(context, ApiPurpose.Transcription)
        repo.update(meeting.id) { it.copy(status = MeetingStatus.Transcribing) }
        val dir = repo.dir(meeting.id)
        val all = mutableListOf<TranscriptSegment>()
        meeting.segments.forEachIndexed { i, segment ->
            setProgress(meeting.id, i.toFloat() / meeting.segments.size)
            val cacheName = "transcript-part-${i + 1}.json"
            val cached = repo.readJson(meeting.id, cacheName)
            val part = if (cached != null) {
                TranscriptSegment.listFromJson(cached)
            } else {
                val audio = File(dir, segment.file)
                if (!audio.exists()) throw PipelineException("audio")
                SttClient.transcribeMeeting(provider, key, audio, segment.mime)
                    .map { it.copy(startMs = it.startMs + segment.startMs, speaker = it.speaker?.let { s -> partSpeaker(i, s, meeting.segments.size) }) }
                    .also { repo.writeJson(meeting.id, cacheName, TranscriptSegment.listToJson(it)) }
            }
            all += part
        }
        setProgress(meeting.id, 1f)
        if (all.isEmpty()) throw PipelineException("empty")
        repo.writeJson(meeting.id, MeetingRepository.TRANSCRIPT, TranscriptSegment.listToJson(all))
        meeting.segments.indices.forEach { File(dir, "transcript-part-${it + 1}.json").delete() }
        return all
    }

    /**
     * Метки дикторов у провайдера действуют только внутри одного запроса. Для многосегментной встречи
     * добавляем номер части, чтобы модель протокола не склеила разных людей по совпавшей метке.
     */
    private fun partSpeaker(part: Int, speaker: String, parts: Int): String =
        if (parts > 1) "${part + 1}.$speaker" else speaker

    // --- Протокол ---

    private suspend fun summarize(context: Context, meeting: Meeting, transcript: List<TranscriptSegment>) {
        val repo = MeetingRepository.get(context)
        val (provider, key) = credentials(context, ApiPurpose.Processing)
        repo.update(meeting.id) { it.copy(status = MeetingStatus.Summarizing) }
        setProgress(meeting.id, null)
        val protocol = ProtocolGenerator.generate(provider, key, meeting, transcript)
        repo.writeJson(meeting.id, MeetingRepository.PROTOCOL, protocol)
        val title = protocol.optString("title").trim()
        // Название из протокола заменяет только автоматическое («פגישה 28.09.26 בשעה 14:30»).
        if (title.isNotEmpty() && meeting.source == MeetingSource.Recorded && meeting.title.isAutoTitle()) {
            repo.update(meeting.id) { it.copy(title = title) }
        }
    }

    private fun String.isAutoTitle(): Boolean = Regex("""\d{2}\.\d{2}\.\d{2} \S+ \d{2}:\d{2}$""").containsMatchIn(this)

    private fun credentials(context: Context, purpose: ApiPurpose): Pair<AiProvider, String> {
        val provider = ApiSelection(context).selected(purpose)
        val key = ApiKeyStore(context).get(ApiSelection.keyId(purpose, provider))
            ?: throw PipelineException("no_key:${purpose.id}")
        return provider to key
    }

    private fun setProgress(id: String, value: Float?) {
        _progress.value = if (value == null) _progress.value - id else _progress.value + (id to value)
    }
}
