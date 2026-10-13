package com.autoedit.ai.edit

import kotlin.math.min

data class SourceClip(val uri: String, val durationMs: Long)
data class Cut(val uri: String, val inMs: Long, val outMs: Long, val speed: Float, val effectIndex: Int) {
    val outputDurationMs: Long get() = ((outMs - inMs) / speed).toLong()
}
data class EditSettings(
    val durationMs: Long = 15_000,
    val cutEveryMs: Long = 850,
    val speed: Float = 1f,
    val intensity: Int = 65,
    val musicStartMs: Long = 0,
    val musicVolume: Float = 0.85f,
    val saturation: Int = -10,
    val lightness: Int = -8,
    val resolutionHeight: Int = 1920,
    val fps: Int = 30,
    val zoom: Boolean = true,
    val keepOriginalAudio: Boolean = false
)

object AutoPlanner {
    /** Beats are absolute positions within the original music file. */
    fun plan(sources: List<SourceClip>, cfg: EditSettings, beats: List<Long>, candidates: Map<String, List<ShotCandidate>> = emptyMap()): List<Cut> {
        require(sources.isNotEmpty()) { "Выберите хотя бы одно видео" }
        require(cfg.durationMs in 1_000L..120_000L)
        require(cfg.speed in 0.5f..2f)
        val valid = sources.filter { it.durationMs >= 500L }
        require(valid.isNotEmpty()) { "Все видео слишком короткие" }
        val interval = cfg.cutEveryMs.coerceIn(350L, 3500L)
        val usable = beats.map { it - cfg.musicStartMs }.filter { it in 300 until cfg.durationMs }.sorted()
        val edges = mutableListOf(0L)
        var t = 0L
        while (cfg.durationMs - t > interval * 1.5) {
            val next = usable.firstOrNull { it in (t + interval * 0.55).toLong()..(t + interval * 1.5).toLong() }
                ?: (t + interval)
            if (next <= t || next >= cfg.durationMs) break
            edges += next
            t = next
        }
        edges += cfg.durationMs
        val cursors = LongArray(valid.size) { (valid[it].durationMs / 8).coerceAtMost(1200) }
        val used = IntArray(valid.size)
        val result = mutableListOf<Cut>()
        for (i in 0 until edges.lastIndex) {
            val requestedOutput = edges[i + 1] - edges[i]
            if (requestedOutput <= 0) continue
            // Prefer a source long enough for the requested cut; loop through the inputs.
            val idx = (i until i + valid.size).map { it % valid.size }
                .firstOrNull { valid[it].durationMs - 10 >= requestedOutput * cfg.speed } ?: (i % valid.size)
            val src = valid[idx]
            val maxInput = src.durationMs - 10
            // Avoid clipping past the end even with short input clips.
            val effectiveSpeed = min(cfg.speed, (maxInput.toFloat() / requestedOutput)).coerceAtLeast(0.5f)
            val inputLength = (requestedOutput * effectiveSpeed).toLong().coerceAtMost(maxInput)
            if (inputLength <= 0) continue
            val maxStart = (src.durationMs - inputLength).coerceAtLeast(0)
            val selected = candidates[src.uri].orEmpty()
            val suggested = if (selected.isEmpty()) null
                else selected[used[idx] % selected.size].centerMs - inputLength / 3
            used[idx]++
            val begin = (suggested ?: cursors[idx]).coerceIn(0L, maxStart)
            result += Cut(src.uri, begin, begin + inputLength, effectiveSpeed, i)
            cursors[idx] = if (maxStart == 0L) 0 else (begin + inputLength + 250) % (maxStart + 1)
        }
        require(result.isNotEmpty()) { "Не удалось выбрать фрагменты" }
        return result
    }
}
