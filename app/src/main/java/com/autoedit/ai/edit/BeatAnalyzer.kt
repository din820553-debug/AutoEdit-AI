package com.autoedit.ai.edit

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class BeatAnalysis(val bpm: Int, val beatTimesMs: List<Long>)

/**
 * Lightweight OFFLINE beat estimation: decodes up to 50 s of local music,
 * forms ~20 ms RMS-energy frames, detects onsets, estimates tempo by
 * autocorrelation, and finds the strongest beat phase.
 * Not equivalent to an audio production-grade onset detector.
 */
object BeatAnalyzer {
    fun analyze(context: Context, uri: Uri): BeatAnalysis? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var decoderStarted = false
        try {
            extractor.setDataSource(context, uri, null)
            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrack = i
                    format = f
                    break
                }
            }
            if (audioTrack == -1 || format == null) return null
            extractor.selectTrack(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
            decoderStarted = true

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var windowSamples = max(1, (sampleRate * channels / 50))
            var sumSquares = 0.0
            var count = 0
            val energies = ArrayList<Double>(3000)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var spins = 0

            while (!outputDone && energies.size < 2500 && spins++ < 25000) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0 || extractor.sampleTime > 50_000_000) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val index = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = decoder.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        windowSamples = max(1, sampleRate * channels / 50)
                    }
                    else -> if (index >= 0) {
                        val bytes = decoder.getOutputBuffer(index)
                        if (bytes != null && info.size > 0) {
                            bytes.order(ByteOrder.LITTLE_ENDIAN)
                            bytes.position(info.offset)
                            bytes.limit(info.offset + info.size)
                            if (encoding == AudioFormat.ENCODING_PCM_16BIT) {
                                val samples = bytes.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                while (samples.hasRemaining()) {
                                    val s = samples.get().toDouble() / 32768.0
                                    sumSquares += s * s
                                    count++
                                    if (count >= windowSamples) {
                                        energies += sqrt(sumSquares / count)
                                        count = 0
                                        sumSquares = 0.0
                                    }
                                }
                            } else if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val samples = bytes.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                                while (samples.hasRemaining()) {
                                    val s = samples.get().toDouble().coerceIn(-1.0, 1.0)
                                    sumSquares += s * s
                                    count++
                                    if (count >= windowSamples) {
                                        energies += sqrt(sumSquares / count)
                                        count = 0
                                        sumSquares = 0.0
                                    }
                                }
                            } else return null
                        }
                        outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        decoder.releaseOutputBuffer(index, false)
                    }
                }
            }
            return estimate(energies)
        } catch (_: Exception) {
            return null
        } finally {
            if (decoderStarted) runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Each RMS sample is a 20ms audio window. */
    internal fun estimate(energy: List<Double>): BeatAnalysis? {
        if (energy.size < 200) return null
        val mean = energy.average()
        if (mean <= 0.0001) return null
        val peaks = DoubleArray(energy.size)
        for (i in 2 until energy.size) {
            val baseline = (energy[i-1] + energy[i-2]) / 2
            peaks[i] = max(0.0, energy[i] - baseline)
        }
        if (peaks.maxOrNull()!! < 0.0001) return null

        var bestLag = 25
        var bestScore = -1.0
        // 20ms bins; between 72 and 180 BPM.
        for (lag in 17..42) {
            var score = 0.0
            for (i in lag until peaks.size) score += peaks[i] * peaks[i-lag]
            score /= (peaks.size - lag).coerceAtLeast(1)
            if (score > bestScore) { bestScore = score; bestLag = lag }
        }
        // Fine phase selection modulo detected beat interval.
        var phase = 0
        var strongest = -1.0
        for (shift in 0 until bestLag) {
            var score = 0.0
            var cursor = shift
            while (cursor < peaks.size) {
                score += peaks[cursor]
                cursor += bestLag
            }
            if (score > strongest) { strongest = score; phase = shift }
        }
        val bpm = (3000f / bestLag).toInt().coerceIn(70, 180)
        val beats = generateSequence(phase * 20L) { it + bestLag * 20L }
            .takeWhile { it < energy.size * 20L }.toList()
        return BeatAnalysis(bpm, beats)
    }

    fun manualGrid(bpm: Int, phaseMs: Long, durationMs: Long): List<Long> {
        val period = 60_000L / bpm.coerceIn(60, 220)
        return generateSequence(phaseMs.coerceAtLeast(0)) { it + period }
            .takeWhile { it < durationMs }.toList()
    }
}
