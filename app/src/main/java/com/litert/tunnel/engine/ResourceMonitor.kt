package com.litert.tunnel.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.os.SystemClock

data class ResourceSample(
    val cpuPercent: Float,   // 0–100, normalized across all cores
    val ramPercent: Float,   // 0–100
    val ramUsedMb: Long,
    val ramTotalMb: Long,
)

class ResourceMonitor(context: Context) {

    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    // Number of CPU cores — used to normalize multi-core CPU time to 0–100%
    private val nCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    // Seed with current values so the first sample delta is meaningful
    private var prevCpuMs  = Process.getElapsedCpuTime()
    private var prevWallMs = SystemClock.elapsedRealtime()

    fun sample(): ResourceSample {
        val mem    = memInfo()
        val usedMb = (mem.totalMem - mem.availMem) / (1024 * 1024)
        val totMb  = mem.totalMem / (1024 * 1024)
        return ResourceSample(
            cpuPercent = readCpuPercent(),
            ramUsedMb  = usedMb,
            ramTotalMb = totMb,
            ramPercent = if (totMb > 0) usedMb * 100f / totMb else 0f,
        )
    }

    private fun readCpuPercent(): Float {
        val cpuMs  = Process.getElapsedCpuTime()   // total CPU ms across all threads
        val wallMs = SystemClock.elapsedRealtime()

        val deltaCpu  = cpuMs  - prevCpuMs
        val deltaWall = wallMs - prevWallMs

        prevCpuMs  = cpuMs
        prevWallMs = wallMs

        // Normalize by core count: on 8-core device all active = 800ms CPU / 100ms wall → 100%
        return if (deltaWall > 0)
            (deltaCpu * 100f / (deltaWall * nCores)).coerceIn(0f, 100f)
        else 0f
    }

    private fun memInfo(): ActivityManager.MemoryInfo {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info
    }
}
