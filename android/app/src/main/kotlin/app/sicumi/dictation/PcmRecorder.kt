package app.sicumi.dictation

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Запись 16 кГц / моно / PCM16 в WAV. Для прототипа дополнительно считает пиковую громкость
 * и проверяет, не «заглушила» ли система микрофон (ограничения while-in-use).
 */
class PcmRecorder(private val outFile: File) {

    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    @Volatile var peak: Int = 0
        private set
    @Volatile var samples: Long = 0
        private set
    /** null — неизвестно (API < 29), true — система отдаёт тишину вместо звука. */
    @Volatile var silenced: Boolean? = null
        private set

    @SuppressLint("MissingPermission") // разрешение проверяется до вызова
    fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4,
            )
        } catch (e: Exception) {
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        try {
            r.startRecording()
        } catch (e: Exception) {
            r.release()
            return false
        }
        record = r
        running = true
        thread = Thread({ loop(r, minBuf) }, "sicumi-recorder").apply { start() }
        return true
    }

    private fun loop(r: AudioRecord, minBuf: Int) {
        val shorts = ShortArray(minBuf)
        val bytes = ByteBuffer.allocate(minBuf * 2).order(ByteOrder.LITTLE_ENDIAN)
        FileOutputStream(outFile).use { out ->
            out.write(ByteArray(WAV_HEADER_SIZE))
            while (running) {
                val n = r.read(shorts, 0, shorts.size)
                if (n > 0) {
                    bytes.clear()
                    for (i in 0 until n) {
                        val s = shorts[i]
                        val a = abs(s.toInt())
                        if (a > peak) peak = a
                        bytes.putShort(s)
                    }
                    out.write(bytes.array(), 0, n * 2)
                    samples += n
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    val cfg = r.activeRecordingConfiguration
                    if (cfg != null) silenced = (silenced == true) || cfg.isClientSilenced
                }
            }
        }
        writeWavHeader(outFile, samples)
    }

    fun stop() {
        running = false
        thread?.join(2000)
        thread = null
        record?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        record = null
    }

    private fun writeWavHeader(file: File, sampleCount: Long) {
        val dataLen = sampleCount * 2
        val header = ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt((36 + dataLen).toInt())
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1) // PCM
            putShort(1) // mono
            putInt(RATE)
            putInt(RATE * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(dataLen.toInt())
        }
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header.array())
        }
    }

    companion object {
        const val RATE = 16_000
        private const val WAV_HEADER_SIZE = 44
    }
}
