package com.litert.tunnel.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class DownloadProgress(
    val progressFraction: Float,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBps: Float = 0f,
    val isDone: Boolean = false,
    val error: String? = null,
)

class ModelDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
) {
    /**
     * Downloads [url] into [destFile], emitting progress.
     * Resumes if [destFile] already exists (sends Range header).
     * Validates final size against [minValidBytes]; deletes file and emits error if too small.
     */
    fun download(
        url: String,
        destFile: File,
        minValidBytes: Long,
    ): Flow<DownloadProgress> = flow {
        val existingBytes = if (destFile.exists()) destFile.length() else 0L

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "LiteRT-Tunnel/1.0")
            .apply { if (existingBytes > 0) header("Range", "bytes=$existingBytes-") }
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            emit(DownloadProgress(0f, 0L, 0L, error = e.message ?: "Network error"))
            return@flow
        }

        if (!response.isSuccessful && response.code != 206) {
            emit(DownloadProgress(0f, 0L, 0L, error = "HTTP ${response.code}: ${response.message}"))
            return@flow
        }

        val totalBytes: Long = when (response.code) {
            206 -> response.header("Content-Range")
                ?.substringAfterLast('/')
                ?.toLongOrNull()
                ?: (response.body?.contentLength()?.let { it + existingBytes } ?: existingBytes)
            else -> response.body?.contentLength()?.takeIf { it > 0 } ?: 0L
        }

        val body = response.body ?: run {
            emit(DownloadProgress(0f, 0L, totalBytes, error = "Empty response body"))
            return@flow
        }

        destFile.parentFile?.mkdirs()
        val buffer = ByteArray(16_384)
        var downloadedBytes = existingBytes
        var lastSpeedCheck = System.currentTimeMillis()
        var bytesAtLastCheck = downloadedBytes

        FileOutputStream(destFile, existingBytes > 0).use { out ->
            body.byteStream().use { ins ->
                while (true) {
                    val read = ins.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    downloadedBytes += read

                    val now = System.currentTimeMillis()
                    if (now - lastSpeedCheck >= 500) {
                        val elapsed = (now - lastSpeedCheck) / 1000f
                        val speed = (downloadedBytes - bytesAtLastCheck) / elapsed
                        val fraction = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
                        emit(
                            DownloadProgress(
                                progressFraction = fraction,
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes,
                                speedBps = speed,
                            )
                        )
                        lastSpeedCheck = now
                        bytesAtLastCheck = downloadedBytes
                    }
                }
            }
        }

        if (destFile.length() < minValidBytes) {
            destFile.delete()
            emit(
                DownloadProgress(
                    progressFraction = 0f,
                    downloadedBytes = 0L,
                    totalBytes = totalBytes,
                    error = "Downloaded file is too small (${destFile.length()} < $minValidBytes). File deleted.",
                )
            )
            return@flow
        }

        emit(
            DownloadProgress(
                progressFraction = 1f,
                downloadedBytes = destFile.length(),
                totalBytes = totalBytes,
                isDone = true,
            )
        )
    }.flowOn(Dispatchers.IO)
}
