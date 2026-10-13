package com.autoedit.ai.edit

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** Lightweight local visual scoring. Not object recognition or trained ML. */
data class ShotCandidate(val sourceUri: String, val centerMs: Long, val score: Double)

object ShotSelector {
    /**
     * Samples up to 10 small frames per video and ranks well-exposed, detailed frames.
     * Does not upload, retain or share decoded video frames.
     */
    fun select(context: Context, sources: List<SourceClip>): Map<String, List<ShotCandidate>> =
        sources.associate { clip ->
            val retriever = MediaMetadataRetriever()
            val candidates = mutableListOf<ShotCandidate>()
            try {
                retriever.setDataSource(context, Uri.parse(clip.uri))
                val count = min(10, (clip.durationMs / 1500L).toInt().coerceAtLeast(2))
                for (i in 1..count) {
                    if (Thread.currentThread().isInterrupted) break
                    val position = (clip.durationMs * i) / (count + 1)
                    val frame = retriever.getScaledFrameAtTime(
                        position * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 64, 64
                    ) ?: continue
                    try {
                        candidates += ShotCandidate(clip.uri, position, scoreFrame(frame))
                    } finally {
                        frame.recycle()
                    }
                }
            } catch (_: Exception) {
                // Missing or unsupported frames should never prevent editing.
            } finally {
                runCatching { retriever.release() }
            }
            val picks = candidates.sortedByDescending { it.score }.fold(mutableListOf<ShotCandidate>()) { chosen, item ->
                if (chosen.size < 6 && chosen.none { abs(it.centerMs - item.centerMs) < 850 }) chosen += item
                chosen
            }
            clip.uri to picks
        }

    /** Scores 0..100 based on exposure and image detail. */
    internal fun scoreLuma(pixels: DoubleArray, width: Int, height: Int): Double {
        if (width < 2 || height < 2 || pixels.size < width * height) return 0.0
        val mean = pixels.average()
        var variance = 0.0
        var edges = 0.0
        var samples = 0
        for (y in 0 until height) for (x in 0 until width) {
            val pos = y * width + x
            val pixel = pixels[pos]
            variance += (pixel - mean) * (pixel - mean)
            if (x > 0) { edges += abs(pixel - pixels[pos - 1]); samples++ }
            if (y > 0) { edges += abs(pixel - pixels[pos - width]); samples++ }
        }
        val detail = if (samples > 0) edges / samples else 0.0
        val contrast = sqrt(variance / pixels.size)
        val exposure = when {
            mean < 15 -> (mean / 15.0).coerceAtLeast(0.0)
            mean > 240 -> ((255.0 - mean) / 15.0).coerceAtLeast(0.0)
            else -> 1.0
        }
        return ((detail * 1.5 + contrast * 0.65) * exposure).coerceIn(0.0, 100.0)
    }

    private fun scoreFrame(bitmap: Bitmap): Double {
        val w = bitmap.width
        val h = bitmap.height
        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)
        val y = DoubleArray(w * h) { i ->
            val p = argb[i]
            val r = (p ushr 16) and 255
            val g = (p ushr 8) and 255
            val b = p and 255
            0.2126 * r + 0.7152 * g + 0.0722 * b
        }
        return scoreLuma(y, w, h)
    }
}
