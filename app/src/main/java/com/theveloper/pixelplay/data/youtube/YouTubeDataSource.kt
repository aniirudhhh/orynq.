package com.theveloper.pixelplay.data.youtube

import android.net.Uri
import androidx.media3.common.C
import com.metrolist.innertubex.extraction.ExtractedStream
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import kotlinx.coroutines.runBlocking
import java.io.IOException

/** Keep only the failure category; signed URLs and underlying HTTP exceptions stay private. */
class YouTubeAudioIOException(val networkFailure: Boolean, message: String) : IOException(message)

internal fun youtubeNetworkFailure(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(12).any {
    it is java.net.UnknownHostException || it is java.net.SocketTimeoutException ||
        it is java.net.SocketException || it is java.io.EOFException ||
        (it is com.metrolist.innertubex.extraction.StreamResolveException && it.reason.name.contains("NETWORK"))
}

/** Resolves at each media open, on ExoPlayer's loader thread, keeping headers scoped to that track. */
@UnstableApi
class YouTubeDataSource(private val repository: YouTubeRepository) : BaseDataSource(true) {
    private var upstream: DefaultHttpDataSource? = null
    private lateinit var original: DataSpec
    private lateinit var stream: ExtractedStream
    private var position = 0L
    private var end = C.LENGTH_UNSET.toLong()
    private var chunkRemaining = 0L
    private var bounded = false
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val id = dataSpec.uri.host ?: throw IOException("Missing YouTube track ID")
        original = dataSpec
        position = dataSpec.position
        try {
            for (attempt in 0..2) {
                stream = runBlocking { repository.stream(id, refresh = attempt > 0) }
                bounded = stream.requireBoundedRange || stream.useRangeChunks
                end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) position + dataSpec.length
                    else stream.contentLengthBytes ?: C.LENGTH_UNSET.toLong()
                if (bounded && (end < 0 || position >= end)) throw IOException("Unsupported stream length")
                try {
                    val length = openChunk()
                    transferStarted(dataSpec)
                    return if (bounded) end - position else length
                } catch (error: HttpDataSource.InvalidResponseCodeException) {
                    upstream?.close()
                    upstream = null
                    timber.log.Timber.tag("YouTubeProvider").w("Audio HTTP status=%d client=%s", error.responseCode, stream.clientName)
                    if (error.responseCode !in listOf(401, 403, 410)) throw error
                    runBlocking { repository.rejectStream(id, stream) }
                    if (attempt == 2) throw error
                }
            }
            throw IOException("YouTube audio unavailable")
        } catch (error: Exception) {
            timber.log.Timber.tag("YouTubeProvider").w("Audio open failed: %s", error::class.java.simpleName)
            if (error is com.metrolist.innertubex.extraction.StreamResolveException) {
                timber.log.Timber.tag("YouTubeProvider").w("Extraction reason: %s", error.reason.name)
            }
            upstream?.close()
            upstream = null
            // Signed stream URLs must not appear in application error logs.
            throw YouTubeAudioIOException(youtubeNetworkFailure(error), "Unable to open YouTube audio. Try another track or retry.")
        }
    }
    private fun openChunk(): Long {
        val source = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(stream.headers.filterKeys { !it.equals("Range", ignoreCase = true) })
            .createDataSource()
        upstream = source
        val spec = if (bounded) {
            chunkRemaining = minOf(stream.rangeChunkSizeBytes.coerceIn(64_000L, 1_048_576L), end - position)
            original.buildUpon().setUri(Uri.parse(stream.audioUrl)).setPosition(position).setLength(chunkRemaining).build()
        } else original.withUri(Uri.parse(stream.audioUrl))
        val length = source.open(spec)
        if (bounded) {
            val contentRange = source.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", true) }
                ?.value?.firstOrNull()
            if (source.responseCode != 206 || contentRange?.startsWith("bytes $position-${position + chunkRemaining - 1}/") != true) {
                throw IOException("Invalid audio range response")
            }
        }
        return length
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bounded && position >= end) return C.RESULT_END_OF_INPUT
        if (bounded && chunkRemaining == 0L) {
            upstream?.close()
            openChunk()
        }
        val count = try {
            upstream?.read(buffer, offset, if (bounded) minOf(length.toLong(), chunkRemaining).toInt() else length)
                ?: throw IOException("Audio is not open")
        } catch (error: IOException) { throw YouTubeAudioIOException(youtubeNetworkFailure(error), "YouTube audio was interrupted. Please retry.") }
        if (bounded && count == C.RESULT_END_OF_INPUT && chunkRemaining > 0) throw IOException("Incomplete audio range")
        if (count > 0) {
            position += count
            if (bounded) chunkRemaining -= count
            bytesTransferred(count)
        }
        return count
    }
    override fun getUri(): Uri? = if (upstream != null && ::original.isInitialized) original.uri else null
    override fun getResponseHeaders(): Map<String, List<String>> = upstream?.responseHeaders.orEmpty()
        .filterKeys { it.lowercase() in setOf("content-type", "content-length", "content-range", "accept-ranges") }
    override fun close() {
        val source = upstream ?: return
        upstream = null
        try { source.close() } finally { transferEnded() }
    }
}
