package com.andrerinas.headunitrevived.decoder

import android.os.SystemClock
import android.view.Surface
import com.andrerinas.headunitrevived.utils.AppLog

class FfmpegHevcDecoder(
    private val surface: Surface,
    private val width: Int,
    private val height: Int
) {
    private var nativeHandle: Long = 0

    val isStarted: Boolean
        get() = nativeHandle != 0L

    fun start(): Boolean {
        if (!libraryLoaded) {
            AppLog.e("FFmpeg HEVC decoder native library is not loaded")
            return false
        }
        if (!isAvailable()) {
            AppLog.e("FFmpeg HEVC decoder is not available. Package FFmpeg headers/libs for this ABI.")
            return false
        }
        if (!surface.isValid) {
            AppLog.e("FFmpeg HEVC decoder cannot start: surface is invalid")
            return false
        }

        nativeHandle = nativeCreate(surface, width, height, recommendedThreadCount())
        if (nativeHandle == 0L) {
            AppLog.e("FFmpeg HEVC decoder failed to initialize")
            return false
        }
        return true
    }

    fun decode(buffer: ByteArray, offset: Int, size: Int): Int {
        val handle = nativeHandle
        if (handle == 0L) return 0
        return nativeDecode(handle, buffer, offset, size, SystemClock.elapsedRealtimeNanos() / 1000L)
    }

    fun stop() {
        val handle = nativeHandle
        nativeHandle = 0
        if (handle != 0L && libraryLoaded) {
            nativeRelease(handle)
        }
    }

    private fun recommendedThreadCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return cores.coerceIn(2, 4)
    }

    companion object {
        private val libraryLoaded: Boolean = try {
            System.loadLibrary("hur_soft_hevc")
            true
        } catch (e: UnsatisfiedLinkError) {
            AppLog.e("Failed to load hur_soft_hevc", e)
            false
        }

        fun isAvailable(): Boolean {
            return libraryLoaded && nativeIsAvailable()
        }

        private external fun nativeIsAvailable(): Boolean
        private external fun nativeCreate(surface: Surface, width: Int, height: Int, threadCount: Int): Long
        private external fun nativeDecode(handle: Long, buffer: ByteArray, offset: Int, size: Int, presentationTimeUs: Long): Int
        private external fun nativeRelease(handle: Long)
    }
}
