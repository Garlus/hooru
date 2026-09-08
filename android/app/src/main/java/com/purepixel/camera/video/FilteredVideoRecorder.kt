package com.purepixel.camera.video

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface

private const val VideoRelativePath = "DCIM/Camera/"

/**
 * Owns the MP4 container and audio encoder. Video frames are supplied by the
 * OpenGL renderer through [inputSurface], so the recorded file contains the
 * same Hooru look as the live preview.
 */
class FilteredVideoRecorder(context: Context) {
    private val appContext = context.applicationContext
    private var recorder: MediaRecorder? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var pendingUri: Uri? = null
    private var recorderSurface: Surface? = null

    val inputSurface: Surface?
        get() = recorderSurface

    @SuppressLint("MissingPermission")
    @Synchronized
    fun prepare(includeAudio: Boolean) {
        check(recorder == null) { "A video recording is already prepared" }
        val resolver = appContext.contentResolver
        val uri = resolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "hooru_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, VideoRelativePath)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        ) ?: error("MediaStore entry could not be created")

        try {
            val output = resolver.openFileDescriptor(uri, "w")
                ?: error("MediaStore file could not be opened")
            descriptor = output
            val nextRecorder = MediaRecorder(appContext)
            recorder = nextRecorder
            nextRecorder.apply {
                if (includeAudio) setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(VIDEO_WIDTH, VIDEO_HEIGHT)
                setVideoFrameRate(VIDEO_FRAME_RATE)
                setVideoEncodingBitRate(VIDEO_BIT_RATE)
                if (includeAudio) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioChannels(1)
                    setAudioSamplingRate(48_000)
                    setAudioEncodingBitRate(128_000)
                }
                setOutputFile(output.fileDescriptor)
                prepare()
            }
            pendingUri = uri
            recorderSurface = nextRecorder.surface
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            releaseResources()
            throw error
        }
    }

    @Synchronized
    fun start() {
        recorder?.start() ?: error("Video recorder is not prepared")
    }

    /** Stops and publishes the current video, or removes it when finalization fails. */
    @Synchronized
    fun stopAndPublish(): Uri? {
        val uri = pendingUri ?: return null
        val resolver = appContext.contentResolver
        val stoppedCleanly = runCatching { recorder?.stop() }.isSuccess
        releaseResources()
        return if (stoppedCleanly) {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null,
                null
            )
            uri
        } else {
            resolver.delete(uri, null, null)
            null
        }.also { pendingUri = null }
    }

    @Synchronized
    fun discard() {
        val uri = pendingUri
        releaseResources()
        if (uri != null) appContext.contentResolver.delete(uri, null, null)
        pendingUri = null
    }

    private fun releaseResources() {
        recorderSurface?.release()
        recorderSurface = null
        runCatching { recorder?.reset() }
        recorder?.release()
        recorder = null
        descriptor?.close()
        descriptor = null
    }

    companion object {
        // Portrait 4:3. Both dimensions are encoder-friendly multiples of 16/8.
        const val VIDEO_WIDTH = 1080
        const val VIDEO_HEIGHT = 1440
        const val VIDEO_FRAME_RATE = 30
        const val VIDEO_BIT_RATE = 14_000_000
    }
}
