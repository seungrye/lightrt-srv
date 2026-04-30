package com.litert.tunnel

import app.cash.turbine.test
import com.litert.tunnel.repository.TunnelRepository
import com.litert.tunnel.repository.TunnelStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TunnelRepositoryTest {

    private lateinit var repo: TunnelRepository

    @BeforeEach
    fun setUp() {
        repo = TunnelRepository()
    }

    @Test
    fun `initial state is IDLE`() = runTest {
        repo.state.test {
            val state = awaitItem()
            assertEquals(TunnelStatus.IDLE, state.status)
            assertNull(state.error)
            assertEquals(0L, state.requestCount)
            cancel()
        }
    }

    @Test
    fun `setLoading transitions to LOADING`() = runTest {
        repo.state.test {
            awaitItem() // IDLE
            repo.setLoading()
            assertEquals(TunnelStatus.LOADING, awaitItem().status)
            cancel()
        }
    }

    @Test
    fun `setRunning transitions to RUNNING with port and backend`() = runTest {
        repo.state.test {
            awaitItem() // IDLE
            repo.setRunning(port = 8080, backendName = "GPU", modelName = "gemma-4-e2b")
            val state = awaitItem()
            assertEquals(TunnelStatus.RUNNING, state.status)
            assertEquals(8080, state.port)
            assertEquals("GPU", state.backendName)
            assertEquals("gemma-4-e2b", state.modelName)
            assertNull(state.error)
            cancel()
        }
    }

    @Test
    fun `setError transitions to ERROR with message`() = runTest {
        repo.state.test {
            awaitItem() // IDLE
            repo.setError("Engine init failed")
            val state = awaitItem()
            assertEquals(TunnelStatus.ERROR, state.status)
            assertEquals("Engine init failed", state.error)
            cancel()
        }
    }

    @Test
    fun `setStopped transitions to STOPPED`() = runTest {
        repo.setRunning(port = 8080, backendName = "CPU", modelName = "model")
        repo.state.test {
            awaitItem() // RUNNING
            repo.setStopped()
            assertEquals(TunnelStatus.STOPPED, awaitItem().status)
            cancel()
        }
    }

    @Test
    fun `appendLog increments requestCount and keeps last 100 entries`() = runTest {
        repeat(110) { i ->
            repo.appendLog(
                com.litert.tunnel.repository.RequestLog(
                    endpoint = "/v1/chat/completions",
                    statusCode = 200,
                    responseTimeMs = i.toLong()
                )
            )
        }
        val state = repo.state.value
        assertEquals(110L, state.requestCount)
        assertEquals(100, state.recentLogs.size)
        // Most recent entry is last
        assertEquals(109L, state.recentLogs.last().responseTimeMs)
    }

    @Test
    fun `can transition from ERROR back to LOADING`() = runTest {
        repo.setError("boom")
        repo.state.test {
            awaitItem() // ERROR
            repo.setLoading()
            val state = awaitItem()
            assertEquals(TunnelStatus.LOADING, state.status)
            assertNull(state.error)
            cancel()
        }
    }

    @Test
    fun `can transition from STOPPED back to LOADING`() = runTest {
        repo.setStopped()
        repo.state.test {
            awaitItem() // STOPPED
            repo.setLoading()
            assertEquals(TunnelStatus.LOADING, awaitItem().status)
            cancel()
        }
    }

    @Test
    fun `setRunning clears previous error`() = runTest {
        repo.setError("previous error")
        repo.setRunning(port = 8081, backendName = "CPU", modelName = "model")
        val state = repo.state.value
        assertNull(state.error)
        assertEquals(TunnelStatus.RUNNING, state.status)
    }

    @Test
    fun `appendLog on RUNNING state preserves other fields`() = runTest {
        repo.setRunning(port = 8080, backendName = "GPU", modelName = "gemma")
        repo.appendLog(
            com.litert.tunnel.repository.RequestLog(
                endpoint = "/health",
                statusCode = 200,
                responseTimeMs = 5
            )
        )
        val state = repo.state.value
        assertEquals(TunnelStatus.RUNNING, state.status)
        assertEquals(8080, state.port)
        assertEquals(1L, state.requestCount)
        assertNotNull(state.recentLogs.firstOrNull())
    }
}
