package app.sicumi.meetings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Встречи хранятся только на устройстве: filesDir/meetings/<id>/
 *   meeting.json — метаданные, аудио-сегменты, transcript.json, protocol.json.
 * Список держится в памяти как StateFlow и обновляется при каждом сохранении.
 */
class MeetingRepository private constructor(context: Context) {

    private val root = File(context.filesDir, "meetings").apply { mkdirs() }
    private val lock = Any()
    private val _meetings = MutableStateFlow(loadAll())
    val meetings: StateFlow<List<Meeting>> = _meetings.asStateFlow()

    fun dir(id: String): File = File(root, id).apply { mkdirs() }

    fun get(id: String): Meeting? = _meetings.value.firstOrNull { it.id == id }

    fun create(title: String, source: MeetingSource, status: MeetingStatus): Meeting {
        val meeting = Meeting(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = System.currentTimeMillis(),
            durationMs = 0,
            source = source,
            status = status,
            segments = emptyList(),
            bookmarks = emptyList(),
        )
        save(meeting)
        return meeting
    }

    fun save(meeting: Meeting) = synchronized(lock) {
        writeJson(File(dir(meeting.id), META), meeting.toJson())
        _meetings.value = (_meetings.value.filterNot { it.id == meeting.id } + meeting).sortedByDescending { it.createdAt }
    }

    fun update(id: String, change: (Meeting) -> Meeting): Meeting? = synchronized(lock) {
        val current = get(id) ?: return null
        change(current).also { save(it) }
    }

    fun delete(id: String) = synchronized(lock) {
        File(root, id).deleteRecursively()
        _meetings.value = _meetings.value.filterNot { it.id == id }
    }

    fun readJson(id: String, name: String): JSONObject? {
        val file = File(dir(id), name)
        if (!file.exists()) return null
        return try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    fun writeJson(id: String, name: String, json: JSONObject) = writeJson(File(dir(id), name), json)

    private fun writeJson(file: File, json: JSONObject) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun loadAll(): List<Meeting> {
        val list = root.listFiles()?.mapNotNull { dir ->
            val file = File(dir, META)
            if (!file.exists()) return@mapNotNull null
            try {
                Meeting.fromJson(JSONObject(file.readText()))
            } catch (e: Exception) {
                null
            }
        }.orEmpty()
        // Запись, прерванная падением процесса, остаётся «записанной» — её можно обработать.
        return list.map {
            if (it.status == MeetingStatus.Recording && it.segments.isNotEmpty()) it.copy(status = MeetingStatus.Recorded) else it
        }.sortedByDescending { it.createdAt }
    }

    companion object {
        const val META = "meeting.json"
        const val TRANSCRIPT = "transcript.json"
        const val PROTOCOL = "protocol.json"

        @Volatile private var instance: MeetingRepository? = null

        fun get(context: Context): MeetingRepository =
            instance ?: synchronized(this) {
                instance ?: MeetingRepository(context.applicationContext).also { instance = it }
            }
    }
}
