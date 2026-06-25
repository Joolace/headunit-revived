package com.andrerinas.headunitrevived.aap

import com.andrerinas.headunitrevived.aap.protocol.messages.Messages
import com.andrerinas.headunitrevived.decoder.VideoDecoder
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import java.nio.ByteBuffer

internal class AapVideo(private val videoDecoder: VideoDecoder, private val settings: Settings, private val onFrameCorrupted: () -> Unit) {

    private data class PendingDecodeFrame(
        val data: ByteArray,
        val forceSoftware: Boolean,
        val codecName: String
    )

    private val messageBuffer = ByteBuffer.allocate(
        if (settings.videoCodec == VideoDecoder.CodecType.H265.mimeType) {
            Messages.DEF_BUFFER_LENGTH * 64 // ~8MB for H.265 support
        } else {
            Messages.DEF_BUFFER_LENGTH * 16 // ~2MB for H.264 legacy support
        }
    )
    private var legacyAssembledBuffer: ByteArray? = null
    private var isFrameCorrupt = false
    private var lastKeyframeRequestMs = 0L
    private val decodeLock = Object()
    private var decodeThread: Thread? = null
    private var decodeThreadRunning = true
    private var pendingDecodeFrame: PendingDecodeFrame? = null
    private var droppedPendingFrames = 0
    private var lastDropLogMs = 0L

    private val useLowLatencySoftwareDecode: Boolean
        get() = settings.forceSoftwareDecoding &&
                settings.softwareVideoDecoder == Settings.SoftwareVideoDecoder.BUNDLED_FFMPEG

    private fun markCorruptAndRequestRecovery() {
        if (!isFrameCorrupt) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastKeyframeRequestMs > 1000) {
                lastKeyframeRequestMs = now
                AppLog.w("AapVideo: Frame corrupted, requesting keyframe to recover stream")
                onFrameCorrupted()
            }
        }
        isFrameCorrupt = true
    }

    private fun findStartCode(buf: ByteArray, offset: Int): Int {
        if (offset + 3 > buf.size) return -1
        if (buf[offset].toInt() == 0 && buf[offset + 1].toInt() == 0) {
            if (buf[offset + 2].toInt() == 1) return 3 // 3-byte start code
            if (offset + 4 <= buf.size && buf[offset + 2].toInt() == 0 && buf[offset + 3].toInt() == 1) return 4 // 4-byte start code
        }
        return -1
    }

    fun process(message: AapMessage): Boolean {

        val flags = message.flags.toInt()
        val buf = message.data
        val len = message.size

        when (flags) {
            11 -> {
                // Single fragment frame - corruption only affects this frame
                isFrameCorrupt = false
                messageBuffer.clear()
                
                // Timestamp Indication (Offset 10)
                val sc10 = findStartCode(buf, 10)
                if (len > 10 + sc10 && sc10 > 0) {
                    decodeFrame(buf, 10, len - 10)
                    return true
                }
                
                // Media Indication or Config (Offset 2)
                val sc2 = findStartCode(buf, 2)
                if (len > 2 + sc2 && sc2 > 0) {
                    decodeFrame(buf, 2, len - 2)
                    return true
                }
                AppLog.w("AapVideo: Dropped Flag 11 packet. len=$len")
            }
            9 -> {
                // First fragment - reset corruption state for the new frame
                isFrameCorrupt = false
                messageBuffer.clear()

                // Timestamp Indication (Offset 10)
                val sc10 = findStartCode(buf, 10)
                if (len > 10 + sc10 && sc10 > 0) {
                    messageBuffer.put(message.data, 10, message.size - 10)
                    return true
                }
                // Media Indication (Offset 2)
                val sc2 = findStartCode(buf, 2)
                if (len > 2 + sc2 && sc2 > 0) {
                    messageBuffer.put(message.data, 2, message.size - 2)
                    return true
                }
            }
            8 -> {
                if (isFrameCorrupt) return true // Skip fragments of an already corrupt frame

                // Middle fragment - append to buffer with overflow detection
                if (messageBuffer.remaining() >= message.size) {
                    messageBuffer.put(message.data, 0, message.size)
                } else {
                    AppLog.e("AapVideo: Fragment overflow (Flag 8)! Size ${message.size} exceeds remaining ${messageBuffer.remaining()}. Invalidating frame.")
                    markCorruptAndRequestRecovery()
                    messageBuffer.clear()
                }
                return true
            }
            10 -> {
                if (isFrameCorrupt) return true // Skip fragments of an already corrupt frame

                // Last fragment - append, assemble, and decode
                if (messageBuffer.remaining() >= message.size) {
                    messageBuffer.put(message.data, 0, message.size)
                } else {
                    AppLog.e("AapVideo: Final fragment overflow (Flag 10)! Invalidating frame.")
                    markCorruptAndRequestRecovery()
                    messageBuffer.clear()
                    return true
                }
                
                messageBuffer.flip()
                val assembledSize = messageBuffer.limit()
                
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
                    if (legacyAssembledBuffer == null || legacyAssembledBuffer!!.size < assembledSize) {
                        legacyAssembledBuffer = ByteArray(assembledSize + 1024)
                    }
                    messageBuffer.get(legacyAssembledBuffer!!, 0, assembledSize)
                    decodeFrame(legacyAssembledBuffer!!, 0, assembledSize)
                } else {
                    decodeFrame(messageBuffer.array(), 0, assembledSize)
                }
                
                messageBuffer.clear()
                return true
            }
        }

        return false
    }

    fun release() {
        synchronized(decodeLock) {
            decodeThreadRunning = false
            pendingDecodeFrame = null
            decodeLock.notifyAll()
        }
        try {
            decodeThread?.interrupt()
            decodeThread?.join(500)
        } catch (_: Exception) {
        }
        decodeThread = null
    }

    private fun decodeFrame(buffer: ByteArray, offset: Int, size: Int) {
        if (!useLowLatencySoftwareDecode) {
            videoDecoder.decode(buffer, offset, size, settings.forceSoftwareDecoding, settings.videoCodec)
            return
        }

        ensureDecodeThread()

        val frame = ByteArray(size)
        System.arraycopy(buffer, offset, frame, 0, size)

        synchronized(decodeLock) {
            if (pendingDecodeFrame != null) {
                if (videoDecoder.lastFrameRenderedMs == 0L) {
                    droppedPendingFrames++
                    logDroppedPendingFramesIfNeeded()
                    return
                }
                droppedPendingFrames++
                logDroppedPendingFramesIfNeeded()
            }
            pendingDecodeFrame = PendingDecodeFrame(frame, settings.forceSoftwareDecoding, settings.videoCodec)
            decodeLock.notifyAll()
        }
    }

    private fun ensureDecodeThread() {
        if (decodeThread?.isAlive == true) return
        synchronized(decodeLock) {
            if (decodeThread?.isAlive == true) return
            decodeThreadRunning = true
            decodeThread = Thread({ decodeLoop() }, "AapVideo-LowLatencyDecode").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            AppLog.i("AapVideo: low-latency software decode thread started")
        }
    }

    private fun decodeLoop() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
        } catch (_: Exception) {
        }

        while (true) {
            val frame = synchronized(decodeLock) {
                while (decodeThreadRunning && pendingDecodeFrame == null) {
                    try {
                        decodeLock.wait()
                    } catch (_: InterruptedException) {
                        if (!decodeThreadRunning) return
                    }
                }
                if (!decodeThreadRunning) return
                val pending = pendingDecodeFrame
                pendingDecodeFrame = null
                pending
            } ?: continue

            try {
                videoDecoder.decode(frame.data, 0, frame.data.size, frame.forceSoftware, frame.codecName)
            } catch (e: Exception) {
                AppLog.e("AapVideo: low-latency decode failed", e)
            }
        }
    }

    private fun logDroppedPendingFramesIfNeeded() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastDropLogMs >= 1000L) {
            AppLog.w("AapVideo: dropped $droppedPendingFrames pending video frame(s) to reduce latency")
            droppedPendingFrames = 0
            lastDropLogMs = now
        }
    }
}
