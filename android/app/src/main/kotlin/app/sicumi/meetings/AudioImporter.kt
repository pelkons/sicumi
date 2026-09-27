package app.sicumi.meetings

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Импорт готового аудио (из выбора файла или «Поделиться»).
 * Файл копируется во внутреннее хранилище встречи: доступ к Uri может пропасть.
 * Перекодирование в сегменты 16 кГц делает конвейер обработки (этап Preparing).
 */
object AudioImporter {

    suspend fun import(context: Context, uri: Uri): Meeting? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = displayName(context, uri)
        val title = name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: MeetingTitles.default(context, System.currentTimeMillis())
        val repo = MeetingRepository.get(context)
        val meeting = repo.create(title, MeetingSource.Imported, MeetingStatus.Recording)
        val ext = name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 2..5 } ?: "audio"
        val target = File(repo.dir(meeting.id), "source.$ext")
        try {
            resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                ?: throw IllegalStateException("no stream")
        } catch (e: Exception) {
            repo.delete(meeting.id)
            return@withContext null
        }
        val duration = durationMs(target)
        if (duration <= 0L) {
            repo.delete(meeting.id)
            return@withContext null
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        repo.update(meeting.id) {
            it.copy(
                status = MeetingStatus.Recorded,
                durationMs = duration,
                segments = listOf(AudioSegment(target.name, 0, mime)),
            )
        }
    }

    fun durationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    } ?: uri.lastPathSegment
}
