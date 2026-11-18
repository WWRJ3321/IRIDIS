package com.iris.localai.voice

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.IOException
import java.nio.ByteOrder

/** Decodes any platform-supported audio file (mp3, m4a/aac, wav, ogg, flac, amr…) to mono float PCM. */
object AudioFileDecoder {
    data class Pcm(val samples: FloatArray, val sampleRate: Int)

    /** Stops after [maxSec] seconds of audio so a long file never fills memory. */
    fun decode(context: Context, uri: Uri, maxSec: Float = 30f): Pcm {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("文件里没有音频")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: throw IOException("音频格式未知")
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(mime)
            val out = FloatArrayBuilder()
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            try {
                codec.configure(fmt, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                val limit = { (maxSec * rate).toInt() }
                while (!outputDone) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        o >= 0 -> {
                            val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val fb = buf.asFloatBuffer()
                                while (fb.remaining() >= channels) {
                                    var s = 0f; repeat(channels) { s += fb.get() }; out.add(s / channels)
                                }
                            } else {
                                val sb = buf.asShortBuffer()
                                while (sb.remaining() >= channels) {
                                    var s = 0f; repeat(channels) { s += sb.get() / 32768f }; out.add(s / channels)
                                }
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || out.size >= limit()) outputDone = true
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            if (out.size == 0) throw IOException("无法解码这个音频文件")
            return Pcm(out.toArray(), rate)
        } finally {
            ex.release()
        }
    }

    private class FloatArrayBuilder {
        private var a = FloatArray(1 shl 16)
        var size = 0
            private set
        fun add(v: Float) {
            if (size == a.size) a = a.copyOf(a.size * 2)
            a[size++] = v
        }
        fun toArray() = a.copyOf(size)
    }
}
