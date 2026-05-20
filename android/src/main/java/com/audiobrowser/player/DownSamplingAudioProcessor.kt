package com.audiobrowser.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAX_SAMPLE_RATE = 192_000

class DownSamplingAudioProcessor : AudioProcessor {

  private var activeProcessor = false
  private var inputFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
  private var outputFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
  private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
  private var inputEnded = false
  private var resampleRatio = 1.0
  private var bytesPerSample = 0

  override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
    inputFormat = inputAudioFormat
    activeProcessor = shouldDownsample(inputAudioFormat)
    bytesPerSample = if (inputAudioFormat.channelCount > 0) {
      inputAudioFormat.bytesPerFrame / inputAudioFormat.channelCount
    } else {
      0
    }

    outputFormat = if (activeProcessor) {
      resampleRatio = inputAudioFormat.sampleRate.toDouble() / MAX_SAMPLE_RATE
      AudioProcessor.AudioFormat(
        MAX_SAMPLE_RATE,
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
    if (!inputBuffer.hasRemaining() || !isActive()) {
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
      ceil(inputFrames * MAX_SAMPLE_RATE / inputFormat.sampleRate.toDouble()).toInt(),
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

  override fun queueEndOfStream() {
    inputEnded = true
  }

  override fun getOutput(): ByteBuffer {
    val output = outputBuffer
    outputBuffer = AudioProcessor.EMPTY_BUFFER
    return output
  }

  override fun isEnded() = inputEnded && outputBuffer == AudioProcessor.EMPTY_BUFFER

  override fun flush() {
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

  private fun shouldDownsample(format: AudioProcessor.AudioFormat): Boolean {
    return format.sampleRate > MAX_SAMPLE_RATE && format.channelCount > 0 && isSupportedEncoding(format.encoding)
  }

  private fun isSupportedEncoding(encoding: Int): Boolean {
    return encoding == C.ENCODING_PCM_FLOAT || encoding == C.ENCODING_PCM_16BIT
  }

  private fun readSample(source: ByteBuffer, frameIndex: Int, channelIndex: Int): Float {
    val byteOffset = frameIndex * inputFormat.bytesPerFrame + channelIndex * bytesPerSample
    return when (inputFormat.encoding) {
      C.ENCODING_PCM_16BIT -> source.getShort(byteOffset).toFloat() / 32768f
      C.ENCODING_PCM_FLOAT -> source.getFloat(byteOffset)
      else -> 0f
    }
  }

  private fun writeSample(output: ByteBuffer, sample: Float) {
    val clamped = max(-1f, min(1f, sample))
    when (inputFormat.encoding) {
      C.ENCODING_PCM_16BIT -> output.putShort((clamped * 32767f).roundToInt().toShort())
      C.ENCODING_PCM_FLOAT -> output.putFloat(clamped)
      else -> {}
    }
  }

  private fun appendBuffers(first: ByteBuffer, second: ByteBuffer): ByteBuffer {
    val combined = ByteBuffer.allocateDirect(first.remaining() + second.remaining())
      .order(ByteOrder.nativeOrder())
    combined.put(first)
    combined.put(second)
    combined.flip()
    return combined
  }
}
