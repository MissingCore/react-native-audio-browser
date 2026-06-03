package com.audiobrowser.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import timber.log.Timber

/**
 * Applies replay gain adjustment at the audio buffer level.
 *
 * When replay gain is undefined or results in a volume factor of 1.0 (no adjustment),
 * the buffer is passed through unchanged for efficiency.
 *
 * Supports PCM 16-bit and PCM float audio formats.
 */
@UnstableApi
class ReplayGainAudioProcessor : AudioProcessor {
  companion object {
    private const val GAIN_DB_TO_LINEAR = 20f
    private val NATIVE_ORDER = ByteOrder.nativeOrder()
  }

  private var inputFormat: AudioFormat = AudioFormat.NOT_SET
  private var outputFormat: AudioFormat = AudioFormat.NOT_SET
  private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
  private var inputEnded = false

  /** Volume adjustment factor (1.0 = no adjustment). When null, pass through unchanged. */
  private var volumeFactor = 1f

  /**
   * Sets the replay gain in decibels. When null or resulting in 0dB, no adjustment is applied.
   * Safe to call at any time during playback.
   *
   * @param replayGainDb The replay gain in decibels, or null for no adjustment
   */
  fun setReplayGain(replayGainDb: Double?) {
    val newVolume =
      if (replayGainDb != null) {
        // Convert dB to linear volume: V = 10^(dB/20)
        GAIN_DB_TO_LINEAR.pow(replayGainDb.toFloat() / GAIN_DB_TO_LINEAR)
      } else {
        // No adjustment
        1f
      }

    if (newVolume != volumeFactor) {
      Timber.d("ReplayGain adjustment changed: ${replayGainDb}dB (volume factor: $newVolume)")
      volumeFactor = newVolume
      // Flush existing buffer when gain changes to avoid applying old gain to new samples
      flush()
    }
  }

  override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
    val isSupported =
      inputAudioFormat.encoding in
        setOf(C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_16BIT) &&
        inputAudioFormat.channelCount > 0

    if (!isSupported) {
      throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
    }

    inputFormat = inputAudioFormat
    outputFormat = inputAudioFormat
    return outputFormat
  }

  override fun isActive(): Boolean {
    return outputFormat != AudioFormat.NOT_SET
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    if (!inputBuffer.hasRemaining() || !isActive()) {
      inputBuffer.position(inputBuffer.limit())
      return
    }

    val pos = inputBuffer.position()
    val limit = inputBuffer.limit()
    val bytesToProcess = limit - pos

    // Create output buffer and apply gain adjustment
    val output = ByteBuffer.allocateDirect(bytesToProcess).order(NATIVE_ORDER)

    if (volumeFactor == 1f) {
      // If no adjustment needed, just pass through the buffer unchanged
      output.put(inputBuffer.slice())
    } else {
      when (inputFormat.encoding) {
        C.ENCODING_PCM_16BIT -> processInt16(inputBuffer, output)
        C.ENCODING_PCM_FLOAT -> processFloat(inputBuffer, output)
        else -> {
          inputBuffer.position(limit)
          return
        }
      }
    }

    output.flip()
    outputBuffer = output
    inputBuffer.position(limit)
  }

  override fun queueEndOfStream() {
    inputEnded = true
  }

  override fun getOutput(): ByteBuffer {
    val output = outputBuffer
    outputBuffer = AudioProcessor.EMPTY_BUFFER
    return output
  }

  override fun isEnded(): Boolean {
    return inputEnded && outputBuffer == AudioProcessor.EMPTY_BUFFER
  }

  override fun flush() {
    outputBuffer = AudioProcessor.EMPTY_BUFFER
    inputEnded = false
  }

  override fun reset() {
    flush()
    inputFormat = AudioFormat.NOT_SET
    outputFormat = AudioFormat.NOT_SET
  }

  // MARK: - Audio Processing

  /**
   * Processes 16-bit PCM audio with replay gain adjustment.
   *
   * Samples are clamped to the Int16 range to prevent clipping/popping when amplified.
   */
  private fun processInt16(input: ByteBuffer, output: ByteBuffer) {
    val sampleCount = input.remaining() / 2 // 2 bytes per 16-bit sample

    for (i in 0 until sampleCount) {
      val sample = input.short
      val amplified =
        (sample * volumeFactor)
          .toInt()
          .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
          .toShort()
      output.putShort(amplified)
    }
  }

  /**
   * Processes 32-bit float PCM audio with replay gain adjustment.
   *
   * Samples are clamped to [-1.0, 1.0] to prevent distortion.
   */
  private fun processFloat(input: ByteBuffer, output: ByteBuffer) {
    val sampleCount = input.remaining() / 4 // 4 bytes per float sample

    for (i in 0 until sampleCount) {
      val sample = input.float
      val amplified = (sample * volumeFactor).coerceIn(-1f, 1f)
      output.putFloat(amplified)
    }
  }
}
