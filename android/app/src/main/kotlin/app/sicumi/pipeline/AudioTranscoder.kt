package app.sicumi.pipeline

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder

/**
 * Любой аудио- или видеофайл → сегменты AAC m4a 16 кГц моно ~32 кбит/с по [segmentMs].
 * Нужен для импортированных файлов: они бывают большими (mp3 128 кбит/с, видео), а у
 * провайдеров STT лимиты 20–25 МБ на запрос. Работает только на встроенных кодеках Android.
 */
class AudioTranscoder(
    private val segmentMs: Long,
    private val isCancelled: () -> Boolean = { false },
    private val onProgress: (Float) -> Unit = {},
) {

    /** Возвращает созданные файлы по порядку. [name] — имя i-го сегмента (с 1). */
    fun transcode(input: File, outDir: File, name: (Int) -> String): List<File> {
        val extractor = MediaExtractor()
        extractor.setDataSource(input.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            throw IllegalArgumentException("no audio track")
        }
        extractor.selectTrack(track)
        val inFormat = extractor.getTrackFormat(track)
        val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L
        val decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(inFormat, null, null, 0)
        decoder.start()

        var sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        val resampler = Resampler()
        val segmentSamples = segmentMs * OUT_RATE / 1000
        val files = mutableListOf<File>()
        var encoder: SegmentEncoder? = null

        fun emit(samples: ShortArray, count: Int) {
            var offset = 0
            while (offset < count) {
                val enc = encoder ?: SegmentEncoder(File(outDir, name(files.size + 1))).also {
                    encoder = it
                    files.add(it.file)
                }
                val room = (segmentSamples - enc.samples).toInt()
                val n = minOf(room, count - offset)
                enc.write(samples, offset, n)
                offset += n
                if (enc.samples >= segmentSamples) {
                    enc.finish()
                    encoder = null
                }
            }
        }

        try {
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (isCancelled()) throw InterruptedException("cancelled")
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            if (durationUs > 0) onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                            extractor.advance()
                        }
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val buf = decoder.getOutputBuffer(outIdx)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.order(ByteOrder.nativeOrder())
                            val mono = if (floatPcm) {
                                val fb = buf.asFloatBuffer()
                                val frames = fb.remaining() / channels
                                ShortArray(frames) { f ->
                                    var sum = 0f
                                    for (c in 0 until channels) sum += fb.get(f * channels + c)
                                    ((sum / channels).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                }
                            } else {
                                val sb = buf.asShortBuffer()
                                val frames = sb.remaining() / channels
                                ShortArray(frames) { f ->
                                    var sum = 0
                                    for (c in 0 until channels) sum += sb.get(f * channels + c)
                                    (sum / channels).toShort()
                                }
                            }
                            val out = resampler.process(mono, sampleRate)
                            emit(out, out.size)
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = decoder.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                }
            }
            encoder?.finish()
            encoder = null
            onProgress(1f)
            return files
        } catch (e: Throwable) {
            encoder?.abort()
            files.forEach { it.delete() }
            throw e
        } finally {
            decoder.stop()
            decoder.release()
            extractor.release()
        }
    }

    /** Понижение частоты усреднением по окну (простой ФНЧ) + повторение при повышении. */
    private class Resampler {
        private var nextBoundary = 0.0
        private var index = 0L
        private var acc = 0L
        private var count = 0
        private var last: Short = 0

        fun process(input: ShortArray, inRate: Int): ShortArray {
            val ratio = inRate.toDouble() / OUT_RATE
            val out = ShortArray((input.size / ratio).toInt() + 2)
            var n = 0
            for (x in input) {
                while (index >= nextBoundary) {
                    if (count > 0) last = (acc / count).toInt().toShort()
                    if (nextBoundary > 0.0) {
                        if (n == out.size) break
                        out[n++] = last
                    }
                    acc = 0
                    count = 0
                    nextBoundary += ratio
                }
                acc += x
                count++
                index++
            }
            return out.copyOf(n)
        }
    }

    /** Один выходной файл: AAC-кодер + MPEG-4 муксер. */
    private class SegmentEncoder(val file: File) {
        var samples = 0L
            private set
        private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private var trackIndex = -1
        private var muxerStarted = false
        private val info = MediaCodec.BufferInfo()

        init {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, OUT_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        }

        fun write(data: ShortArray, offset: Int, count: Int) {
            var pos = offset
            val end = offset + count
            while (pos < end) {
                val idx = codec.dequeueInputBuffer(TIMEOUT_US)
                if (idx >= 0) {
                    val buf = codec.getInputBuffer(idx)!!
                    buf.clear()
                    buf.order(ByteOrder.nativeOrder())
                    val n = minOf(buf.remaining() / 2, end - pos)
                    buf.asShortBuffer().put(data, pos, n)
                    codec.queueInputBuffer(idx, 0, n * 2, samples * 1_000_000L / OUT_RATE, 0)
                    samples += n
                    pos += n
                }
                drain(false)
            }
        }

        fun finish() {
            var queued = false
            while (!queued) {
                val idx = codec.dequeueInputBuffer(TIMEOUT_US)
                if (idx >= 0) {
                    codec.queueInputBuffer(idx, 0, 0, samples * 1_000_000L / OUT_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    queued = true
                } else {
                    drain(false)
                }
            }
            drain(true)
            codec.stop()
            codec.release()
            if (muxerStarted) muxer.stop()
            muxer.release()
        }

        fun abort() {
            try { codec.release() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
        }

        private fun drain(untilEos: Boolean) {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEos) return
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)!!
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (info.size > 0 && muxerStarted && !isConfig) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, buf, info)
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
    }

    companion object {
        const val OUT_RATE = 16_000
        private const val TIMEOUT_US = 10_000L
    }
}
