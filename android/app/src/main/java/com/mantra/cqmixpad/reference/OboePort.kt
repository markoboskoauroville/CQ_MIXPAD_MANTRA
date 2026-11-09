/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Kotlin port for CQ MixPad (Mantra), 9.11.2026, of two parts of Google's Oboe (github.com/google/oboe, commit
 * 8c13e5e2552bdf9ccadb4211a48646e32b50eabd, Apache-2.0, THIRD_PARTY/oboe-LICENSE.txt):
 *  - FifoBuffer + FifoControllerBase (src/fifo/): a single-producer single-consumer ring of frames, read and write
 *    counters only ever growing, the two-part copy at the wrap;
 *  - WaveFileWriter (apps/OboeTester/app/src/main/cpp/util/, itself from Phil Burk's JSyn): RIFF/fmt/data header,
 *    PCM24 with the offset-before-cast rounding and clipping.
 * Changed for us: floats instead of bytes in the ring; the writer buffers into a ByteBuffer instead of one virtual
 * call per byte, and patches the RIFF and data sizes when it closes (Oboe leaves INT32_MAX when the length is unknown).
 */
package com.mantra.cqmixpad.reference

import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/** Oboe's FifoBuffer for float frames: one writer thread (the capture), one reader thread (the file writer). */
class FifoBuffer(val samplesPerFrame: Int, val capacityInFrames: Int) {
    private val storage = FloatArray(samplesPerFrame * capacityInFrames)
    private val readCounter = AtomicLong(0); private val writeCounter = AtomicLong(0)
    /** Frames the writer could not put in (the reader fell behind): a dropout in the files. */
    val overruns = AtomicLong(0)

    fun fullFramesAvailable(): Int = (writeCounter.get() - readCounter.get()).toInt()
    fun emptyFramesAvailable(): Int = capacityInFrames - fullFramesAvailable()

    fun write(src: FloatArray, numFrames: Int): Int {
        if (numFrames <= 0) return 0
        val n = minOf(numFrames, emptyFramesAvailable())
        if (n < numFrames) overruns.addAndGet((numFrames - n).toLong())
        val wi = (writeCounter.get() % capacityInFrames).toInt()
        if (wi + n > capacityInFrames) {      // two parts, the first at the end of the storage
            val f1 = capacityInFrames - wi
            System.arraycopy(src, 0, storage, wi * samplesPerFrame, f1 * samplesPerFrame)
            System.arraycopy(src, f1 * samplesPerFrame, storage, 0, (n - f1) * samplesPerFrame)
        } else System.arraycopy(src, 0, storage, wi * samplesPerFrame, n * samplesPerFrame)
        writeCounter.addAndGet(n.toLong())
        return n
    }

    fun read(dst: FloatArray, numFrames: Int): Int {
        if (numFrames <= 0) return 0
        val n = minOf(numFrames, fullFramesAvailable())
        val ri = (readCounter.get() % capacityInFrames).toInt()
        if (ri + n > capacityInFrames) {
            val f1 = capacityInFrames - ri
            System.arraycopy(storage, ri * samplesPerFrame, dst, 0, f1 * samplesPerFrame)
            System.arraycopy(storage, 0, dst, f1 * samplesPerFrame, (n - f1) * samplesPerFrame)
        } else System.arraycopy(storage, ri * samplesPerFrame, dst, 0, n * samplesPerFrame)
        readCounter.addAndGet(n.toLong())
        return n
    }
}

/** Oboe's WaveFileWriter (16 or 24-bit PCM), with the sizes patched at [close]. */
class WaveFileWriter(private val out: FileOutputStream, private val frameRate: Int, private val samplesPerFrame: Int, private val bitsPerSample: Int = 24) {
    private var bytesWritten = 0L
    private var headerWritten = false
    private var bb: ByteBuffer = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
    val dataBytes: Long get() = bytesWritten

    private fun header(dataSize: Int): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerSample = (bitsPerSample + 7) / 8
        h.put('R'.code.toByte()).put('I'.code.toByte()).put('F'.code.toByte()).put('F'.code.toByte())
        // Oboe: "Maximum size is not strictly correct but is commonly used when we do not know the final size."
        h.putInt(if (dataSize > Int.MAX_VALUE - 36) Int.MAX_VALUE else dataSize + 36)
        h.put('W'.code.toByte()).put('A'.code.toByte()).put('V'.code.toByte()).put('E'.code.toByte())
        h.put('f'.code.toByte()).put('m'.code.toByte()).put('t'.code.toByte()).put(' '.code.toByte())
        h.putInt(16); h.putShort(WAVE_FORMAT_PCM.toShort()); h.putShort(samplesPerFrame.toShort()); h.putInt(frameRate)
        h.putInt(frameRate * samplesPerFrame * bytesPerSample); h.putShort((samplesPerFrame * bytesPerSample).toShort()); h.putShort(bitsPerSample.toShort())
        h.put('d'.code.toByte()).put('a'.code.toByte()).put('t'.code.toByte()).put('a'.code.toByte()); h.putInt(dataSize)
        return h.array()
    }

    /** [buffer][start until start+numSamples], interleaved when samplesPerFrame > 1. */
    fun write(buffer: FloatArray, start: Int, numSamples: Int) {
        if (!headerWritten) { out.write(header(Int.MAX_VALUE)); headerWritten = true }
        val need = numSamples * ((bitsPerSample + 7) / 8)
        if (bb.capacity() < need) bb = ByteBuffer.allocate(need).order(ByteOrder.LITTLE_ENDIAN)
        bb.clear()
        for (i in 0 until numSamples) {
            val v = buffer[start + i]
            if (bitsPerSample == 24) {
                // Oboe: "Offset before casting so that we can avoid using floor(). Also round by adding 0.5 so that very
                // small signals go to zero."
                val temp = (PCM24_MAX * v) + 0.5 - PCM24_MIN
                val s = (temp.toInt() + PCM24_MIN).coerceIn(PCM24_MIN, PCM24_MAX)
                bb.put(s.toByte()); bb.put((s shr 8).toByte()); bb.put((s shr 16).toByte())
            } else {
                val temp = (Short.MAX_VALUE * v) + 0.5 - Short.MIN_VALUE
                val s = (temp.toInt() + Short.MIN_VALUE).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                bb.put(s.toByte()); bb.put((s shr 8).toByte())
            }
        }
        out.write(bb.array(), 0, bb.position()); bytesWritten += bb.position()
    }

    /** Ours: the real sizes into the header, then close. */
    fun close() {
        if (!headerWritten) { out.write(header(0)); headerWritten = true }
        out.flush()
        val size = bytesWritten.coerceAtMost((Int.MAX_VALUE - 36).toLong()).toInt()
        out.channel.position(0); out.write(header(size)); out.flush()
        runCatching { out.fd.sync() }; out.close()
    }

    companion object {
        const val WAVE_FORMAT_PCM = 1
        const val PCM24_MIN = -(1 shl 23)
        const val PCM24_MAX = (1 shl 23) - 1
    }
}
