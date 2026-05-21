package com.andrerinas.headunitrevived.decoder

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.andrerinas.headunitrevived.utils.AppLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AudioMixer — Single-AudioTrack mixer for Static Audio Focus mode.
 *
 * When enabled, ALL audio channels (AUDIO/media, AUDIO1/assistant, AUDIO2/navigation)
 * are mixed in software and written to a single AudioTrack (48kHz, 16-bit, stereo).
 *
 * This prevents Chinese head unit firmware from reacting to the creation/destruction
 * of multiple AudioTrack instances, which causes volume routing bugs.
 */
class AudioMixer(
    private val stream: Int = AudioManager.STREAM_MUSIC
) {

    companion object {
        private const val TAG = "AudioMixer"

        // Output format (fixed)
        const val OUTPUT_SAMPLE_RATE = 48000
        const val OUTPUT_CHANNELS = 2      // stereo
        const val OUTPUT_BIT_DEPTH = 16

        // Mix cycle interval in ms — balance between latency and CPU usage
        private const val MIX_INTERVAL_MS = 20L

        // Samples per mix cycle (per channel): 48000 * 0.020 = 960 samples
        private const val SAMPLES_PER_CYCLE = (OUTPUT_SAMPLE_RATE * MIX_INTERVAL_MS / 1000).toInt()

        // Frames per cycle (stereo): 960 * 2 = 1920 shorts
        private const val FRAMES_PER_CYCLE = SAMPLES_PER_CYCLE * OUTPUT_CHANNELS

        // Buffer size multiplier for AudioTrack (8x mix cycle for safety)
        private const val BUFFER_MULTIPLIER = 8
    }

    /**
     * Per-channel configuration and state.
     */
    data class ChannelConfig(
        val sampleRate: Int,        // e.g. 16000 or 48000
        val channelCount: Int       // 1 = mono, 2 = stereo
    )

    // The single AudioTrack
    private var audioTrack: AudioTrack? = null

    // Per-channel configuration
    private val channelConfigs = ConcurrentHashMap<Int, ChannelConfig>()

    // Per-channel PCM circular buffers
    private val channelBuffers = ConcurrentHashMap<Int, ShortCircularBuffer>()

    // Per-channel gain (0.0 to 1.0+)
    private val channelGains = ConcurrentHashMap<Int, Float>()

    // Mixing thread
    private var mixThread: Thread? = null
    private val running = AtomicBoolean(false)

    // Pre-allocated buffers for the mix loop to avoid GC allocation pressure
    private val mixBuffer = IntArray(FRAMES_PER_CYCLE)
    private val tempChannelBuffer = ShortArray(FRAMES_PER_CYCLE)
    private val outputBuffer = ShortArray(FRAMES_PER_CYCLE)

    /**
     * Start the mixer — creates the AudioTrack and starts the mixing thread.
     */
    fun start() {
        if (running.get()) {
            AppLog.i("$TAG: Already running")
            return
        }

        val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
        val dataFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioTrack.getMinBufferSize(OUTPUT_SAMPLE_RATE, channelConfig, dataFormat)
        val bufferSize = maxOf(minBufferSize, FRAMES_PER_CYCLE * 2 * BUFFER_MULTIPLIER)

        try {
            audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()

                val format = AudioFormat.Builder()
                    .setSampleRate(OUTPUT_SAMPLE_RATE)
                    .setChannelMask(channelConfig)
                    .setEncoding(dataFormat)
                    .build()

                AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    stream,
                    OUTPUT_SAMPLE_RATE,
                    channelConfig,
                    dataFormat,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                )
            }

            audioTrack?.play()
            running.set(true)

            mixThread = Thread({
                AppLog.i("$TAG: Mix thread started")
                mixLoop()
                AppLog.i("$TAG: Mix thread stopped")
            }, "AudioMixer-Thread")
            mixThread?.start()

            AppLog.i("$TAG: Started (bufferSize=$bufferSize, minBuffer=$minBufferSize)")
        } catch (e: Exception) {
            AppLog.e("$TAG: Failed to start AudioTrack or mixer thread", e)
        }
    }

    /**
     * Stop the mixer — stops the mixing thread and releases the AudioTrack.
     */
    fun stop() {
        if (!running.getAndSet(false)) return

        try {
            mixThread?.interrupt()
            mixThread?.join(1000)
        } catch (e: Exception) {
            AppLog.e("$TAG: Error joining mix thread", e)
        }
        mixThread = null

        try {
            audioTrack?.stop()
        } catch (e: Exception) {
            AppLog.e("$TAG: Error stopping AudioTrack: ${e.message}")
        }
        try {
            audioTrack?.release()
        } catch (e: Exception) {
            AppLog.e("$TAG: Error releasing AudioTrack: ${e.message}")
        }
        audioTrack = null

        channelBuffers.clear()
        channelConfigs.clear()
        channelGains.clear()

        AppLog.i("$TAG: Stopped and released")
    }

    /**
     * Register a channel with its audio parameters.
     */
    fun registerChannel(channel: Int, sampleRate: Int, channelCount: Int) {
        channelConfigs[channel] = ChannelConfig(sampleRate, channelCount)
        channelBuffers.putIfAbsent(channel, ShortCircularBuffer(96000)) // fits ~1s at 48kHz stereo
        channelGains.putIfAbsent(channel, 1.0f)

        AppLog.i("$TAG: Registered channel $channel (sampleRate=$sampleRate, channels=$channelCount)")
    }

    /**
     * Unregister a channel (e.g. on session end).
     */
    fun unregisterChannel(channel: Int) {
        channelConfigs.remove(channel)
        channelBuffers.remove(channel)
        channelGains.remove(channel)
        AppLog.i("$TAG: Unregistered channel $channel")
    }

    /**
     * Feed raw PCM data for a channel. Called from the wrapper/decoder thread.
     * Data format: 16-bit signed little-endian PCM.
     * Resamples to 48kHz stereo directly on the caller thread to offload calculations.
     */
    fun feed(channel: Int, data: ByteArray, offset: Int, length: Int) {
        val config = channelConfigs[channel] ?: return
        val buffer = channelBuffers[channel] ?: return
        if (length <= 0) return

        val inputShortsSize = length / 2
        if (inputShortsSize <= 0) return

        // 1. Convert bytes (little-endian) to shorts
        val inputShorts = ShortArray(inputShortsSize)
        for (i in 0 until inputShortsSize) {
            val idx = offset + i * 2
            val lo = data[idx].toInt() and 0xFF
            val hi = data[idx + 1].toInt()
            inputShorts[i] = ((hi shl 8) or lo).toShort()
        }

        // 2. Resample / convert channels and write to buffer
        if (config.sampleRate == OUTPUT_SAMPLE_RATE && config.channelCount == OUTPUT_CHANNELS) {
            // No conversion needed, just write directly
            buffer.write(inputShorts, inputShortsSize)
        } else if (config.sampleRate == 16000 && config.channelCount == 1) {
            // Highly optimized path: 16kHz mono to 48kHz stereo (ratio = 3)
            val outputShorts = ShortArray(inputShortsSize * 3 * 2)
            var outIdx = 0
            for (i in 0 until inputShortsSize) {
                val current = inputShorts[i].toInt()
                val next = if (i + 1 < inputShortsSize) inputShorts[i + 1].toInt() else current

                for (j in 0 until 3) {
                    val fraction = j.toFloat() / 3f
                    val interpolated = (current + (next - current) * fraction).toInt().coerceIn(-32768, 32767).toShort()
                    outputShorts[outIdx++] = interpolated // Left
                    outputShorts[outIdx++] = interpolated // Right
                }
            }
            buffer.write(outputShorts, outputShorts.size)
        } else {
            // Generic linear resampling and channel configuration fallback
            val ratio = OUTPUT_SAMPLE_RATE / config.sampleRate
            val inputChannels = config.channelCount
            val inputFrames = inputShortsSize / inputChannels
            val outputFrames = inputFrames * ratio
            val outputShorts = ShortArray(outputFrames * OUTPUT_CHANNELS)

            var outIdx = 0
            for (i in 0 until inputFrames) {
                val currentL = inputShorts[i * inputChannels].toInt()
                val nextL = if (i + 1 < inputFrames) inputShorts[(i + 1) * inputChannels].toInt() else currentL

                val currentR = if (inputChannels == 2) inputShorts[i * 2 + 1].toInt() else currentL
                val nextR = if (inputChannels == 2 && i + 1 < inputFrames) inputShorts[(i + 1) * 2 + 1].toInt() else currentR

                for (j in 0 until ratio) {
                    val fraction = j.toFloat() / ratio
                    val interpolatedL = (currentL + (nextL - currentL) * fraction).toInt().coerceIn(-32768, 32767).toShort()
                    val interpolatedR = (currentR + (nextR - currentR) * fraction).toInt().coerceIn(-32768, 32767).toShort()

                    outputShorts[outIdx++] = interpolatedL
                    outputShorts[outIdx++] = interpolatedR
                }
            }
            buffer.write(outputShorts, outputShorts.size)
        }
    }

    /**
     * Set per-channel gain (for ducking).
     * @param gain 0.0 = silence, 1.0 = full volume
     */
    fun setChannelGain(channel: Int, gain: Float) {
        channelGains[channel] = gain
    }

    /**
     * Get current gain for a channel.
     */
    fun getChannelGain(channel: Int): Float = channelGains[channel] ?: 1.0f

    /**
     * Check if the mixer is currently running.
     */
    fun isRunning(): Boolean = running.get()

    /**
     * Check if a channel is registered.
     */
    fun hasChannel(channel: Int): Boolean = channelConfigs.containsKey(channel)

    // ========================================================================
    // Private mixing logic
    // ========================================================================

    /**
     * Main mixing loop — runs on the dedicated thread.
     * Every MIX_INTERVAL_MS, drains all channel circular buffers,
     * mixes into a single buffer, and writes to the AudioTrack.
     */
    private fun mixLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)

        while (running.get()) {
            val startTime = System.nanoTime()

            // Clear mix buffer
            mixBuffer.fill(0)
            var hasData = false

            // Process each registered channel
            for ((channel, buffer) in channelBuffers) {
                val gain = channelGains[channel] ?: 1.0f

                // Read exactly FRAMES_PER_CYCLE shorts from circular buffer
                val read = buffer.read(tempChannelBuffer, 0, FRAMES_PER_CYCLE)
                if (read > 0) {
                    hasData = true
                    for (i in 0 until read) {
                        mixBuffer[i] += (tempChannelBuffer[i] * gain).toInt()
                    }
                }
            }

            // Clip and write output (always write to keep the AudioTrack active)
            for (i in mixBuffer.indices) {
                outputBuffer[i] = mixBuffer[i].coerceIn(-32768, 32767).toShort()
            }

            val track = audioTrack
            if (track != null) {
                try {
                    track.write(outputBuffer, 0, FRAMES_PER_CYCLE)
                } catch (e: Exception) {
                    AppLog.e(TAG, "Error writing to AudioTrack", e)
                }
            }

            // Sleep dynamically based on execution time
            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000L
            val sleepTime = MIX_INTERVAL_MS - elapsedMs
            if (sleepTime > 0) {
                try {
                    Thread.sleep(sleepTime)
                } catch (_: InterruptedException) {
                    break
                }
            } else {
                Thread.yield()
            }
        }
    }

    /**
     * Thread-safe, garbage-free circular buffer for Short audio samples.
     */
    private class ShortCircularBuffer(capacity: Int) {
        private val buffer = ShortArray(capacity)
        private var head = 0
        private var tail = 0
        private var size = 0

        @Synchronized
        fun write(data: ShortArray, length: Int): Int {
            var written = 0
            for (i in 0 until length) {
                if (size >= buffer.size) {
                    break
                }
                buffer[tail] = data[i]
                tail = (tail + 1) % buffer.size
                size++
                written++
            }
            return written
        }

        @Synchronized
        fun read(out: ShortArray, offset: Int, length: Int): Int {
            var read = 0
            for (i in 0 until length) {
                if (size == 0) {
                    break
                }
                out[offset + i] = buffer[head]
                head = (head + 1) % buffer.size
                size--
                read++
            }
            return read
        }

        @Synchronized
        fun size(): Int = size

        @Synchronized
        fun clear() {
            head = 0
            tail = 0
            size = 0
        }
    }
}
