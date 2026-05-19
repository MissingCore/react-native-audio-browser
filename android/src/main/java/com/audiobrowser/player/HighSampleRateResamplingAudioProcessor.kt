package com.audiobrowser.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAX_OUTPUT_SAMPLE_RATE = 192_000

class HighSampleRateResamplingAudioProcessor : AudioProcessor {
  private var activeProcessor = false
  private var inputFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
  private var outputFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
  private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
  private var inputEnded = false
  private var resampleRatio = 1.0

  override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
    inputFormat = inputAudioFormat
    activeProcessor = shouldResample(inputAudioFormat)

    outputFormat = if (activeProcessor) {
      resampleRatio = inputAudioFormat.sampleRate.toDouble() / MAX_OUTPUT_SAMPLE_RATE
      AudioProcessor.AudioFormat(
        MAX_OUTPUT_SAMPLE_RATE,
        inputAudioFormat.channelCount,
        inputAudioFormat.encoding,
      )
    } else {
      AudioProcessor.AudioFormat.NOT_SET
    }

    return outputFormat
  }

  override fun isActive() = activeProcessor

  override fun queueInput(inputBuffer: ByteBuffer) {
    if (!activeProcessor || !inputBuffer.hasRemaining()) {
      inputBuffer.position(inputBuffer.limit())
      return
    }

    val source = inputBuffer.slice().order(ByteOrder.nativeOrder())
    val frameSize = inputFormat.bytesPerFrame
    val inputFrames = source.remaining() / frameSize
    if (inputFrames == 0) {
      inputBuffer.position(inputBuffer.limit())
      return
    }

    val outputFrames = max(
      1,
      kotlin.math.ceil(inputFrames * MAX_OUTPUT_SAMPLE_RATE / inputFormat.sampleRate.toDouble()).toInt(),
    )
    val outputBytes = outputFrames * frameSize
    val output = ByteBuffer.allocateDirect(outputBytes).order(ByteOrder.nativeOrder())

    for (outputFrame in 0 until outputFrames) {
      val position = outputFrame * resampleRatio
      val leftIndex = min(inputFrames - 1, position.toInt())
      val rightIndex = min(inputFrames - 1, leftIndex + 1)
      val fraction = (position - leftIndex).toFloat()

      for (channel in 0 until inputFormat.channelCount) {
        val leftSample = readSample(source, leftIndex, channel)
        val rightSample = readSample(source, rightIndex, channel)
        val interpolated = if (leftIndex == rightIndex) leftSample else leftSample + (rightSample - leftSample) * fraction
        writeSample(output, interpolated)
      }
    }

    output.flip()
    outputBuffer = if (outputBuffer.hasRemaining()) {
      appendBuffers(outputBuffer, output)
    } else {
      output
    }

    inputBuffer.position(inputBuffer.limit())
  }

  override fun getOutput(): ByteBuffer {
    val output = outputBuffer
    outputBuffer = AudioProcessor.EMPTY_BUFFER
    return output
  }

  override fun isEnded() = inputEnded && outputBuffer == AudioProcessor.EMPTY_BUFFER

  override fun queueEndOfStream() {
    inputEnded = true
  }

  override fun flush(streamMetadata: AudioProcessor.StreamMetadata) {
    outputBuffer = AudioProcessor.EMPTY_BUFFER
    inputEnded = false
  }

  override fun reset() {
    activeProcessor = false
    inputFormat = AudioProcessor.AudioFormat.NOT_SET
    outputFormat = AudioProcessor.AudioFormat.NOT_SET
    outputBuffer = AudioProcessor.EMPTY_BUFFER
    inputEnded = false
    resampleRatio = 1.0
  }

  private fun shouldResample(format: AudioProcessor.AudioFormat): Boolean {
    return format.sampleRate > MAX_OUTPUT_SAMPLE_RATE && isLinearPcm(format.encoding)
  }

  private fun isLinearPcm(encoding: Int): Boolean {
    return when (encoding) {
      C.ENCODING_PCM_16BIT,
      C.ENCODING_PCM_24BIT,
      C.ENCODING_PCM_32BIT,
      C.ENCODING_PCM_FLOAT -> true
      else -> false
    }
  }

  private fun readSample(source: ByteBuffer, frameIndex: Int, channelIndex: Int): Float {
    val samplePosition = (frameIndex * inputFormat.channelCount + channelIndex) * inputFormat.bytesPerFrame / inputFormat.channelCount
    return when (inputFormat.encoding) {
      C.ENCODING_PCM_16BIT -> source.getShort(samplePosition).toFloat() / Short.MAX_VALUE
      C.ENCODING_PCM_24BIT -> {
        val value = (source.get(samplePosition + 2).toInt() shl 24) or
          ((source.get(samplePosition + 1).toInt() and 0xFF) shl 16) or
          ((source.get(samplePosition).toInt() and 0xFF) shl 8)
        (value.toInt() shr 8).toFloat() / 8_388_608f
      }
      C.ENCODING_PCM_32BIT -> source.getInt(samplePosition).toFloat() / 2_147_483_648f
      C.ENCODING_PCM_FLOAT -> source.getFloat(samplePosition)
      else -> 0f
    }
  }

  private fun writeSample(output: ByteBuffer, sample: Float) {
    when (inputFormat.encoding) {
      C.ENCODING_PCM_16BIT -> {
        val clamped = max(Short.MIN_VALUE.toFloat(), min(Short.MAX_VALUE.toFloat(), sample * Short.MAX_VALUE))
        output.putShort(clamped.roundToInt().toShort())
      }
      C.ENCODING_PCM_24BIT -> {
        val scaled = max(-8_388_608f, min(8_388_607f, sample * 8_388_607f)).roundToInt()
        output.put((scaled shr 0 and 0xFF).toByte())
        output.put((scaled shr 8 and 0xFF).toByte())
        output.put((scaled shr 16 and 0xFF).toByte())
      }
      C.ENCODING_PCM_32BIT -> {
        val clamped = max(Int.MIN_VALUE.toFloat(), min(Int.MAX_VALUE.toFloat(), sample * 2_147_483_647f))
        output.putInt(clamped.roundToInt())
      }
      C.ENCODING_PCM_FLOAT -> output.putFloat(sample)
      else -> {
        // No-op for unsupported encodings.
      }
    }
  }

  private fun appendBuffers(first: ByteBuffer, second: ByteBuffer): ByteBuffer {
    val combined = ByteBuffer.allocateDirect(first.remaining() + second.remaining()).order(ByteOrder.nativeOrder())
    combined.put(first)
    combined.put(second)
    combined.flip()
    return combined
  }
}
