package com.nexus.vision.benchmark

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks timing metrics for pipeline operations.
 * Thread-safe for concurrent tile processing.
 */
class PerformanceTracker {

    companion object {
        private const val TAG = "PerfTracker"
        private const val MAX_HISTORY = 100
    }

    data class TimingRecord(
        val id: String,
        val startTimeNs: Long,
        var endTimeNs: Long = 0L,
        var durationMs: Long = 0L
    )

    data class SessionSummary(
        val sessionId: String,
        val totalTimeMs: Long,
        val tileCount: Int,
        val avgTileTimeMs: Long,
        val minTileTimeMs: Long,
        val maxTileTimeMs: Long,
        val ncnnTileCount: Int,
        val cpuTileCount: Int,
        val ncnnAvgMs: Long,
        val metadata: Map<String, Any>
    )

    private val activeSessions = ConcurrentHashMap<String, TimingRecord>()
    private val activeTiles = ConcurrentHashMap<String, TimingRecord>()
    private val sessionHistory = mutableListOf<SessionSummary>()
    private val tileTimings = ConcurrentHashMap<String, MutableList<Long>>()

    fun startSession(sessionId: String) {
        activeSessions[sessionId] = TimingRecord(
            id = sessionId,
            startTimeNs = System.nanoTime()
        )
        tileTimings[sessionId] = mutableListOf()
    }

    fun startTileProcessing(tileId: String) {
        activeTiles[tileId] = TimingRecord(
            id = tileId,
            startTimeNs = System.nanoTime()
        )
    }

    fun endTileProcessing(tileId: String) {
        val record = activeTiles.remove(tileId) ?: return
        record.endTimeNs = System.nanoTime()
        record.durationMs = (record.endTimeNs - record.startTimeNs) / 1_000_000

        val sessionId = activeSessions.keys.firstOrNull() ?: return
        tileTimings[sessionId]?.add(record.durationMs)
    }

    fun endSession(sessionId: String, metadata: Map<String, Any> = emptyMap()) {
        val session = activeSessions.remove(sessionId) ?: return
        val endTime = System.nanoTime()
        val totalMs = (endTime - session.startTimeNs) / 1_000_000

        val tileTimes = tileTimings.remove(sessionId) ?: emptyList()

        val summary = SessionSummary(
            sessionId = sessionId,
            totalTimeMs = totalMs,
            tileCount = tileTimes.size,
            avgTileTimeMs = if (tileTimes.isNotEmpty()) tileTimes.average().toLong() else 0L,
            minTileTimeMs = tileTimes.minOrNull() ?: 0L,
            maxTileTimeMs = tileTimes.maxOrNull() ?: 0L,
            ncnnTileCount = (metadata["route_c_ncnn"] as? Int) ?: 0,
            cpuTileCount = (metadata["route_c_cpu"] as? Int) ?: 0,
            ncnnAvgMs = (metadata["ncnn_avg_ms"] as? Long) ?: 0L,
            metadata = metadata
        )

        synchronized(sessionHistory) {
            sessionHistory.add(summary)
            if (sessionHistory.size > MAX_HISTORY) {
                sessionHistory.removeAt(0)
            }
        }

        Log.i(TAG, "Session '$sessionId': total=${totalMs}ms, " +
              "tiles=${tileTimes.size}, avg=${summary.avgTileTimeMs}ms, " +
              "min=${summary.minTileTimeMs}ms, max=${summary.maxTileTimeMs}ms")
    }

    fun getHistory(): List<SessionSummary> = synchronized(sessionHistory) {
        sessionHistory.toList()
    }

    fun getLatestSummary(): SessionSummary? = synchronized(sessionHistory) {
        sessionHistory.lastOrNull()
    }

    fun clear() {
        activeSessions.clear()
        activeTiles.clear()
        tileTimings.clear()
        synchronized(sessionHistory) { sessionHistory.clear() }
    }
}
