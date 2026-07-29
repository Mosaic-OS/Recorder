/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.service

import android.Manifest.permission
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresPermission
import org.lineageos.recorder.utils.PcmConverter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.abs

class HighQualityRecorder : SoundRecording {
    private var record: AudioRecord? = null
    private var file: File? = null
    private var recordingThread: Thread? = null

    @Volatile
    private var maxAmplitude = 0

    @Volatile
    private var isRecording = false

    @Volatile
    private var isPaused = false

    @RequiresPermission(permission.RECORD_AUDIO)
    override fun startRecording(file: File) {
        this.file = file

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLING_RATE)
            .setChannelMask(CHANNEL_IN)
            .setEncoding(FORMAT)
            .build()
        record = AudioRecord(
            MediaRecorder.AudioSource.DEFAULT, audioFormat.sampleRate,
            audioFormat.channelMask, audioFormat.encoding, BUFFER_SIZE
        ).apply {
            startRecording()
        }

        isRecording = true
        isPaused = false

        recordingThread = Thread { recordingThreadImpl() }.apply { start() }
    }

    override fun stopRecording(): Boolean {
        val record = record ?: return false

        isRecording = false

        // Let the recording thread finish writing before the buffer is released.
        recordingThread?.join(THREAD_JOIN_TIMEOUT_MS)
        recordingThread = null

        record.stop()
        record.release()
        this.record = null

        return true
    }

    override fun pauseRecording(): Boolean {
        if (!isRecording) {
            return false
        }

        isPaused = true
        record?.stop()

        return true
    }

    override fun resumeRecording(): Boolean {
        if (!isRecording) {
            return false
        }
        record?.startRecording()
        isPaused = false
        return true
    }

    override val currentAmplitude: Int
        get() {
            return maxAmplitude
        }

    private fun recordingThreadImpl() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        try {
            FileOutputStream(file).use { out ->
                PcmConverter.writeWavHeader(out, SAMPLING_RATE, CHANNEL_IN)

                val buffer = ByteArray(BUFFER_SIZE)
                while (isRecording) {
                    if (isPaused) {
                        // Reading a stopped AudioRecord returns immediately, so idle instead.
                        Thread.sleep(PAUSE_POLL_INTERVAL_MS)
                        continue
                    }

                    val read = record?.read(buffer, 0, BUFFER_SIZE) ?: 0
                    if (read > 0) {
                        out.write(buffer, 0, read)

                        var max = 0
                        var i = 0
                        while (i + 1 < read) {
                            // Little endian 16 bit PCM, decoded without allocating.
                            val sample = (buffer[i + 1].toInt() shl 8) or
                                    (buffer[i].toInt() and 0xFF)
                            val amplitude = abs(sample)
                            if (amplitude > max) {
                                max = amplitude
                            }
                            i += 2
                        }
                        maxAmplitude = max
                    }
                }

                PcmConverter.updateWavHeader(out)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Can't find output file", e)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override val fileExtension = "wav"

    override val mimeType = "audio/wav"

    companion object {
        private const val TAG = "HighQualityRecorder"

        private const val SAMPLING_RATE = 44100
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_STEREO
        private const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val PAUSE_POLL_INTERVAL_MS = 50L
        private const val THREAD_JOIN_TIMEOUT_MS = 1000L
        private val BUFFER_SIZE = AudioRecord.getMinBufferSize(
            SAMPLING_RATE,
            CHANNEL_IN,
            FORMAT
        )
    }
}
