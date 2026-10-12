package com.autoedit.ai.edit

import org.junit.Assert.*
import org.junit.Test

class AutoPlannerTest {
    @Test fun singleSourceCutsToTarget() {
        val cuts = AutoPlanner.plan(listOf(SourceClip("a", 30_000)), EditSettings(durationMs = 15_000), emptyList())
        assertTrue(cuts.size >= 5)
        assertEquals(15_000L, cuts.sumOf { it.outputDurationMs })
        assertTrue(cuts.all { it.inMs >= 0 && it.outMs <= 30_000 })
    }
    @Test fun musicOffsetIsRespected() {
        val cuts = AutoPlanner.plan(listOf(SourceClip("a", 20_000)), EditSettings(durationMs = 5_000,cutEveryMs=900,musicStartMs=10_000),listOf(10_800,11_600,12_400))
        assertEquals(800L, cuts.first().outputDurationMs)
    }
    @Test fun allSourcesUsed() {
        val cuts = AutoPlanner.plan(listOf(SourceClip("a",20_000),SourceClip("b",20_000)),EditSettings(),emptyList())
        assertTrue(cuts.map{it.uri}.toSet().containsAll(setOf("a","b")))
    }
    @Test fun noSourcesRejected() {
        try { AutoPlanner.plan(emptyList(),EditSettings(),emptyList()); fail("should reject") }
        catch (_: IllegalArgumentException) { }
    }
}
