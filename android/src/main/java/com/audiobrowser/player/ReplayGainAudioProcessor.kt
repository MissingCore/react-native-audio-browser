package com.audiobrowser.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.pow

@UnstableApi
class ReplayGainAudioProcessor : BaseAudioProcessor() {
  companion object {
    private val SUPPORTED_ENCODINGS = setOf(
      C.ENCODING_PCM_FLOAT,
      C.ENCODING_PCM_16BIT,
    )
  }

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
        10f.pow(replayGainDb.toFloat() / 20f)
      } else {
        // No adjustment
        1f
      }

    if (newVolume != volumeFactor) {
      volumeFactor = newVolume
      // Flush existing buffer when gain changes to avoid applying old gain to new samples
      flush()
    }
  }

  //#region AudioProcessor Implementation
  override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
    if (inputAudioFormat.encoding !in SUPPORTED_ENCODINGS) {
      throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
    }
    return inputAudioFormat
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val pos = inputBuffer.position()
    val limit = inputBuffer.limit()
    val bytesToProcess = limit - pos

    // Get the output buffer from BaseAudioProcessor
    val output = replaceOutputBuffer(bytesToProcess)

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
    inputBuffer.position(limit)
  }
  //#endregion

  //#region Buffer Helpers
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
  //#endregion
}
