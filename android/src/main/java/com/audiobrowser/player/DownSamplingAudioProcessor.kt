package com.audiobrowser.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@UnstableApi
class DownSamplingAudioProcessor : AudioProcessor {

  companion object {
    private const val MAX_SAMPLE_RATE = 192_000
    private val SUPPORTED_ENCODINGS = setOf(
      C.ENCODING_PCM_FLOAT,
      C.ENCODING_PCM_16BIT,
      C.ENCODING_PCM_24BIT,
      C.ENCODING_PCM_32BIT,
    )

    private val NATIVE_ORDER = ByteOrder.nativeOrder()
  }

  private var inputFormat: AudioFormat = AudioFormat.NOT_SET
  private var outputFormat: AudioFormat = AudioFormat.NOT_SET
  private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
  private var inputEnded = false
  private var resampleRatio = 1.0
  private var bytesPerSample = 0

  override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
    val shouldDownsample = (
      inputAudioFormat.encoding in SUPPORTED_ENCODINGS &&
      inputAudioFormat.channelCount > 0 &&
      inputAudioFormat.sampleRate > MAX_SAMPLE_RATE
    )

    if (!shouldDownsample) {
      reset()
      return inputAudioFormat
    }

    resampleRatio = inputAudioFormat.sampleRate.toDouble() / MAX_SAMPLE_RATE
    bytesPerSample = inputAudioFormat.bytesPerFrame / inputAudioFormat.channelCount

    inputFormat = inputAudioFormat
    outputFormat = AudioFormat(
      MAX_SAMPLE_RATE,
      inputAudioFormat.channelCount,
      inputAudioFormat.encoding,
    )

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

    val source = inputBuffer.slice().order(NATIVE_ORDER)
    val frameSize = inputFormat.bytesPerFrame
    val inputFrames = source.remaining() / frameSize
    if (inputFrames == 0) {
      inputBuffer.position(inputBuffer.limit())
      return
    }

    val outputFrames = max(1, ceil(inputFrames / resampleRatio).toInt())
    val outputBytes = outputFrames * frameSize
    val output = ByteBuffer.allocateDirect(outputBytes).order(NATIVE_ORDER)

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
    resampleRatio = 1.0
    bytesPerSample = 0
  }

  //#region Buffer Helpers
  private fun readSample(source: ByteBuffer, frameIndex: Int, channelIndex: Int): Float {
    val byteOffset = frameIndex * inputFormat.bytesPerFrame + channelIndex * bytesPerSample
    // Guard against invalid offsets to avoid BufferUnderflowException
    if (byteOffset < 0 || byteOffset + bytesPerSample > source.limit()) return 0f

    return when (inputFormat.encoding) {
      C.ENCODING_PCM_16BIT -> source.getShort(byteOffset).toInt().toFloat() / 32768f
      C.ENCODING_PCM_24BIT -> {
        val b0 = source.get(byteOffset).toInt() and 0xFF
        val b1 = source.get(byteOffset + 1).toInt() and 0xFF
        val b2 = source.get(byteOffset + 2).toInt()
        val sampleInt = b0 or (b1 shl 8) or (b2 shl 16)
        sampleInt.toFloat() / 8_388_608f
      }
      C.ENCODING_PCM_32BIT -> source.getInt(byteOffset).toFloat() / 2_147_483_648f
      C.ENCODING_PCM_FLOAT -> source.getFloat(byteOffset)
      else -> 0f
    }
  }

  private fun writeSample(output: ByteBuffer, sample: Float) {
    val clamped = max(-1f, min(1f, sample))
    // Ensure there is enough space in the output buffer
    if (output.remaining() < bytesPerSample) return

    when (inputFormat.encoding) {
      C.ENCODING_PCM_16BIT -> output.putShort((clamped * 32767f).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
      C.ENCODING_PCM_24BIT -> {
        val intSample = (clamped * 8_388_607f).roundToInt().coerceIn(-8_388_608, 8_388_607)
        if (output.order() == ByteOrder.BIG_ENDIAN) {
          output.put((intSample shr 16).toByte())
          output.put((intSample shr 8).toByte())
          output.put(intSample.toByte())
        } else {
          output.put(intSample.toByte())
          output.put((intSample shr 8).toByte())
          output.put((intSample shr 16).toByte())
        }
      }
      C.ENCODING_PCM_32BIT -> {
        val intSample = (clamped * 2_147_483_647f).roundToInt().coerceIn(Int.MIN_VALUE, Int.MAX_VALUE)
        output.putInt(intSample)
      }
      C.ENCODING_PCM_FLOAT -> output.putFloat(clamped)
      else -> {}
    }
  }

  private fun appendBuffers(first: ByteBuffer, second: ByteBuffer): ByteBuffer {
    // If one of the buffers is empty, avoid allocation and return the other
    if (!first.hasRemaining()) return second
    if (!second.hasRemaining()) return first

    val combined = ByteBuffer.allocateDirect(first.remaining() + second.remaining())
      .order(NATIVE_ORDER)
    combined.put(first)
    combined.put(second)
    combined.flip()
    return combined
  }
  //#endregion
}
