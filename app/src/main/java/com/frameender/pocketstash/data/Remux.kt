package com.frameender.pocketstash.data

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Stash makes its MP4 conversions on the fly as a *fragmented* MP4 (no index, no duration up
 * front) so it can stream them while converting. Saved to a file that way, players can't tell
 * how long it is or jump around in it. This copies the audio and video, untouched, into a
 * regular MP4 with a proper index: fast (no re-encoding) and the result seeks normally.
 */
object Remux {

    /** Copies [src] into a seekable MP4 at [dst]. Throws if Android can't read or write it. */
    fun toSeekableMp4(src: File, dst: File) {
        dst.delete()
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(src.path)
            val out = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = out
            val trackMap = IntArray(extractor.trackCount) { -1 }
            var maxSample = 0
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_ROTATION)) {
                    runCatching { out.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION)) }
                }
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxSample = maxOf(maxSample, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                trackMap[i] = out.addTrack(format)
                extractor.selectTrack(i)
            }
            if (trackMap.none { it >= 0 }) throw IOException("No audio or video found in the file")

            // MP4 needs timestamps from zero up; B-frame streams can start slightly below zero.
            var first = Long.MAX_VALUE
            while (true) {
                val t = extractor.sampleTime
                if (t < 0) break
                if (t < first) first = t
                if (!extractor.advance()) break
            }
            val shift = if (first == Long.MAX_VALUE || first >= 0) 0L else -first
            for (i in trackMap.indices) if (trackMap[i] >= 0) extractor.unselectTrack(i)
            for (i in trackMap.indices) if (trackMap[i] >= 0) extractor.selectTrack(i)
            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            out.start()
            started = true
            val buffer = ByteBuffer.allocateDirect(maxOf(maxSample, 8 * 1024 * 1024))
            val info = MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val track = trackMap.getOrElse(extractor.sampleTrackIndex) { -1 }
                if (track >= 0) {
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = (extractor.sampleTime + shift).coerceAtLeast(0)
                    info.flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    out.writeSampleData(track, buffer, info)
                }
                if (!extractor.advance()) break
            }
            // Writes the index; a failure here means the copy isn't usable.
            started = false
            out.stop()
        } catch (e: Exception) {
            dst.delete()
            throw e
        } finally {
            runCatching { if (started) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
        if (!dst.exists() || dst.length() == 0L) throw IOException("The fixed copy came out empty")
    }
}
