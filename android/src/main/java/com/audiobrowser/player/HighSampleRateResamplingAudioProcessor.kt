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
  private var bytesPerSample = 0

  override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
    inputFormat = inputAudioFormat
    activeProcessor = shouldResample(inputAudioFormat)
    bytesPerSample = inputAudioFormat.bytesPerFrame / inputAudioFormat.channelCount

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
    if (!inputBuffer.hasRemaining()) {
      return
    }

    val source = inputBuffer.slice().order(ByteOrder.nativeOrder())
    val frameSize = inputFormat.bytesPerFrame
    val inputFrames = source.remaining() / frameSize
    if (inputFrames == 0) {
      return
    }

    val outputFrames = max(
      1,
      ceil(inputFrames * MAX_OUTPUT_SAMPLE_RATE / inputFormat.sampleRate.toDouble()).toInt(),
    )
    val outputBytes = outputFrames * frameSize
    val output = ByteBuffer.allocateDirect(outputBytes).order(ByteOrder.nativeOrder())

    for (outputFrame in 0 until outputFrames) {
      val position = outputFrame * resampleRatio
      val leftIndex = min(inputFrames - 1, position.toInt())
      val rightIndex = if (leftIndex < inputFrames - 1) leftIndex + 1 else leftIndex
      val fraction = (position - leftIndex).toFloat()

      for (channel in 0 until inputFormat.channelCount) {
        val leftSample = readSample(source, leftIndex, channel)
        val rightSample = if (leftIndex == rightIndex) leftSample else readSample(source, rightIndex, channel)
        val interpolated = leftSample + (rightSample - leftSample) * fraction
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
    bytesPerSample = 0
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
    val byteOffset = frameIndex * inputFormat.bytesPerFrame + channelIndex * bytesPerSample
    return when (inputFormat.encoding) {
      C.ENCODING_PCM_16BIT -> source.getShort(byteOffset).toFloat() / 32768f
      C.ENCODING_PCM_24BIT -> {
        val b0 = source.get(byteOffset).toInt() and 0xFF
        val b1 = source.get(byteOffset + 1).toInt() and 0xFF
        val b2 = source.get(byteOffset + 2).toByte().toInt()
        val value = (b2 shl 16) or (b1 shl 8) or b0
        value.toFloat() / 8_388_608f
      }
      C.ENCODING_PCM_32BIT -> source.getInt(byteOffset).toFloat() / 2_147_483_648f
      C.ENCODING_PCM_FLOAT -> source.getFloat(byteOffset)
      else -> 0f
    }
  }

  private fun writeSample(output: ByteBuffer, sample: Float) {
    val clamped = max(-1f, min(1f, sample))
    when (inputFormat.encoding) {
      C.ENCODING_PCM_16BIT -> {
        output.putShort((clamped * 32767f).roundToInt().toShort())
      }
      C.ENCODING_PCM_24BIT -> {
        val value = (clamped * 8_388_607f).roundToInt()
        output.put((value and 0xFF).toByte())
        output.put(((value shr 8) and 0xFF).toByte())
        output.put(((value shr 16) and 0xFF).toByte())
      }
      C.ENCODING_PCM_32BIT -> {
        output.putInt((clamped * 2_147_483_647f).roundToInt())
      }
      C.ENCODING_PCM_FLOAT -> {
        output.putFloat(clamped)
      }
      else -> {}
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
