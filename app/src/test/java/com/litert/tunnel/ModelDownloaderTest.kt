package com.litert.tunnel

import app.cash.turbine.test
import com.litert.tunnel.download.DownloadProgress
import com.litert.tunnel.download.ModelDownloader
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class ModelDownloaderTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var server: MockWebServer
    private lateinit var downloader: ModelDownloader

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        downloader = ModelDownloader()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun destFile(name: String = "model.litertlm"): File =
        tempDir.resolve(name).toFile()

    private fun fakeModelBytes(size: Int = 1024): ByteArray =
        ByteArray(size) { it.toByte() }

    @Test
    fun `downloads file and emits isDone=true on success`() = runTest {
        val bytes = fakeModelBytes(4096)
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(bytes))
                .setHeader("Content-Length", bytes.size.toString())
        )

        val dest = destFile()
        downloader.download(
            url = server.url("/model.litertlm").toString(),
            destFile = dest,
            minValidBytes = 100L
        ).test {
            var lastItem: DownloadProgress? = null
            while (true) {
                val item = awaitItem()
                lastItem = item
                if (item.isDone) break
            }
            assertTrue(lastItem!!.isDone)
            assertFalse(lastItem!!.error != null)
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(dest.exists())
        assertEquals(bytes.size.toLong(), dest.length())
    }

    @Test
    fun `sends Range header when dest file already exists (resume)`() = runTest {
        val existingBytes = fakeModelBytes(512)
        val remainingBytes = fakeModelBytes(512)
        val dest = destFile()
        dest.writeBytes(existingBytes)

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setBody(Buffer().write(remainingBytes))
                .setHeader("Content-Range", "bytes 512-1023/1024")
                .setHeader("Content-Length", remainingBytes.size.toString())
        )

        downloader.download(
            url = server.url("/model.litertlm").toString(),
            destFile = dest,
            minValidBytes = 100L
        ).test {
            while (true) {
                val item = awaitItem()
                if (item.isDone || item.error != null) break
            }
            cancelAndIgnoreRemainingEvents()
        }

        val request = server.takeRequest()
        assertEquals("bytes=512-", request.getHeader("Range"))
        assertEquals(1024L, dest.length())
    }

    @Test
    fun `emits error progress when server returns non-2xx`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not found"))

        val dest = destFile()
        downloader.download(
            url = server.url("/missing.litertlm").toString(),
            destFile = dest,
            minValidBytes = 100L
        ).test {
            var errorItem: DownloadProgress? = null
            while (true) {
                val item = awaitItem()
                if (item.error != null) {
                    errorItem = item
                    break
                }
                if (item.isDone) break
            }
            assertNotNull(errorItem)
            assertTrue(errorItem!!.error!!.contains("404"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deletes file and emits error when downloaded size below minValidBytes`() = runTest {
        val tinyBytes = fakeModelBytes(50)
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(tinyBytes))
                .setHeader("Content-Length", tinyBytes.size.toString())
        )

        val dest = destFile()
        downloader.download(
            url = server.url("/model.litertlm").toString(),
            destFile = dest,
            minValidBytes = 1000L   // far larger than what we download
        ).test {
            var lastItem: DownloadProgress? = null
            while (true) {
                val item = awaitItem()
                lastItem = item
                if (item.isDone || item.error != null) break
            }
            assertNotNull(lastItem!!.error)
            assertFalse(dest.exists(), "Corrupt file should be deleted")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `progress fraction increases monotonically`() = runTest {
        val bytes = fakeModelBytes(8192)
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(bytes))
                .setHeader("Content-Length", bytes.size.toString())
        )

        val dest = destFile()
        val fractions = mutableListOf<Float>()
        downloader.download(
            url = server.url("/model.litertlm").toString(),
            destFile = dest,
            minValidBytes = 100L
        ).test {
            while (true) {
                val item = awaitItem()
                fractions.add(item.progressFraction)
                if (item.isDone || item.error != null) break
            }
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(fractions.isNotEmpty())
        // Each fraction should be >= previous
        for (i in 1 until fractions.size) {
            assertTrue(fractions[i] >= fractions[i - 1],
                "Fraction decreased: ${fractions[i - 1]} -> ${fractions[i]}")
        }
        assertEquals(1.0f, fractions.last())
    }
}
