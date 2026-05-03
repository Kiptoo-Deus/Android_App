package com.luciano.chordseq

// ═════════════════════════════════════════════════════════════════════════════
//  AudioCapture.kt — Audio Capture (Layer 2) + Pitch Detection (Layer 3)
//  ChordAnds · Luciano Muratore
// ═════════════════════════════════════════════════════════════════════════════
//
//  LAYER 2 — AUDIO CAPTURE
//  ┌─────────────────────────────────────────────────────────────────────────┐
//  │  - Captures audio from the device microphone using AudioRecord API     │
//  │  - Sample rate: 44100 Hz, mono channel, PCM 16-bit encoding            │
//  │  - Records continuously while the user holds the mic button            │
//  │  - Feeds captured audio buffer into Layer 3 pitch detection in         │
//  │    real time via a background thread                                   │
//  │  - Stops capturing when the user cancels or confirms                   │
//  │  - Handles RECORD_AUDIO permission denial gracefully                   │
//  └─────────────────────────────────────────────────────────────────────────┘
//
//  LAYER 3 — PITCH DETECTION
//  ┌─────────────────────────────────────────────────────────────────────────┐
//  │  - Uses the YIN algorithm for pitch detection (best for vocal input)   │
//  │  - Converts detected frequency (Hz) → musical note name               │
//  │    e.g. 440 Hz → A4, 261 Hz → C4                                      │
//  │  - Accumulates detected notes into a list while recording              │
//  │  - Ignores detections below confidence threshold of 0.85               │
//  │  - Exposes detected notes for use by MainActivity / ChordAnalyser      │
//  │  - Reports the currently detected note in real time via callback       │
//  └─────────────────────────────────────────────────────────────────────────┘
//
//  ARCHITECTURE: Follows MVVM-adjacent pattern — AudioCapture is a dedicated
//  processor class (not a ViewModel) so it can be tested independently.
//  MainActivity owns the lifecycle and calls start() / stop() / confirm().
//
//  HOW LAYERS CONNECT:
//  AudioRecord (Layer 2) → fills ShortArray buffer →
//  YINPitchDetector (Layer 3) → emits frequency →
//  noteFromFrequency() → note name → onNoteDetected callback →
//  MainActivity places note in next empty piano roll slot.
//
// ═════════════════════════════════════════════════════════════════════════════

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────
//  AudioCapture — manages microphone recording and real-time pitch detection
// ─────────────────────────────────────────────────────────────────────────────
class AudioCapture(
    private val context         : Context,
    private val onNoteDetected  : (noteName: String, midi: Int) -> Unit,  // called on main thread
    private val onPermissionDenied : () -> Unit
) {
    companion object {
        private const val TAG         = "AudioCapture"
        const val SAMPLE_RATE         = 44100
        private const val CHANNEL     = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING    = AudioFormat.ENCODING_PCM_16BIT
        private const val CONFIDENCE  = 0.85f   // YIN threshold — ignore noisy frames
        private const val BUFFER_SIZE_MULTIPLIER = 4
    }

    // State
    private var audioRecord   : AudioRecord? = null
    private var recordThread  : Thread?      = null
    @Volatile private var isRecording = false

    // Accumulated notes during a recording session
    private val _detectedNotes = mutableListOf<Pair<String, Int>>()  // (name, midi)
    val detectedNotes: List<Pair<String, Int>> get() = _detectedNotes.toList()

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns true if RECORD_AUDIO permission is granted */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * Start recording. Returns false if permission is missing.
     * Calls onNoteDetected() on the main thread whenever a note is detected.
     */
    fun start(): Boolean {
        if (!hasPermission()) { onPermissionDenied(); return false }
        if (isRecording) return true

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufSize = minBuf * BUFFER_SIZE_MULTIPLIER

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL, ENCODING, bufSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialise")
            return false
        }

        _detectedNotes.clear()
        isRecording = true
        audioRecord?.startRecording()

        recordThread = Thread { captureLoop(bufSize) }.also { it.start() }
        Log.d(TAG, "Recording started")
        return true
    }

    /**
     * Stop recording and discard the captured notes.
     * Call this when the user taps ✕.
     */
    fun cancel() {
        stopInternal()
        _detectedNotes.clear()
        Log.d(TAG, "Recording cancelled")
    }

    /**
     * Stop recording and return the accumulated notes.
     * Call this when the user taps ✓.
     * Returns a deduplicated, frequency-sorted list of MIDI notes.
     */
    fun confirm(): List<Int> {
        stopInternal()
        val midi = _detectedNotes
            .map { it.second }
            .distinct()
            .sorted()
        Log.d(TAG, "Recording confirmed. Notes: $midi")
        return midi
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun stopInternal() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recordThread?.join(500)
        recordThread = null
    }

    /**
     * LAYER 2 — continuous capture loop running on a background thread.
     * Reads PCM frames and passes them to the YIN detector.
     */
    private fun captureLoop(bufSize: Int) {
        val buffer    = ShortArray(bufSize / 2)
        val detector  = YINPitchDetector(SAMPLE_RATE, buffer.size)

        while (isRecording) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
            if (read <= 0) continue

            // LAYER 3 — detect pitch in this frame
            val result = detector.detect(buffer, read)
            if (result != null && result.confidence >= CONFIDENCE) {
                val (name, midi) = noteFromFrequency(result.pitchHz)
                if (midi in 36..84) {
                    // Accumulate and notify on main thread
                    _detectedNotes.add(name to midi)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onNoteDetected(name, midi)
                    }
                }
            }
        }
        Log.d(TAG, "Capture loop ended")
    }

    // ── Note from frequency ───────────────────────────────────────────────────

    /**
     * LAYER 3 — converts a frequency in Hz to a note name and MIDI number.
     * Uses equal temperament: A4 = 440 Hz = MIDI 69.
     * e.g. 440.0 → ("A4", 69),  261.6 → ("C4", 60)
     */
    private fun noteFromFrequency(hz: Float): Pair<String, Int> {
        val names   = listOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")
        val midi    = (12.0 * log2(hz / 440.0) + 69.0).roundToInt().coerceIn(0, 127)
        val octave  = (midi / 12) - 1
        val name    = names[midi % 12] + octave
        return name to midi
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  YINPitchDetector — LAYER 3
//  Pure Kotlin implementation of the YIN algorithm.
//  Reference: de Cheveigné & Kawahara (2002), JASA 111(4):1917-1930.
//
//  YIN is preferred over autocorrelation for vocal/instrument input because
//  it suppresses octave errors and handles harmonic-rich signals better.
// ─────────────────────────────────────────────────────────────────────────────
class YINPitchDetector(
    private val sampleRate : Int,
    private val bufferSize : Int
) {
    data class Result(val pitchHz: Float, val confidence: Float)

    private val yinBuffer = FloatArray(bufferSize / 2)

    /**
     * Run YIN on [frameSize] samples from [buffer].
     * Returns null if no clear pitch is found.
     */
    fun detect(buffer: ShortArray, frameSize: Int): Result? {
        val halfLen = minOf(yinBuffer.size, frameSize / 2)

        // Step 1 — Difference function
        difference(buffer, frameSize, halfLen)

        // Step 2 — Cumulative mean normalised difference
        cumulativeMeanNormalisedDifference(halfLen)

        // Step 3 — Absolute threshold (find first dip below 0.15)
        val tau = absoluteThreshold(halfLen) ?: return null

        // Step 4 — Parabolic interpolation for sub-sample accuracy
        val refinedTau = parabolicInterpolation(tau, halfLen)

        // Step 5 — Confidence = 1 - yinBuffer[tau]
        val confidence = 1f - yinBuffer[tau]

        val pitchHz = sampleRate.toFloat() / refinedTau
        if (pitchHz < 50f || pitchHz > 2000f) return null  // out of musical range

        return Result(pitchHz, confidence)
    }

    // ── YIN steps ─────────────────────────────────────────────────────────────

    private fun difference(buffer: ShortArray, frameSize: Int, halfLen: Int) {
        for (tau in 0 until halfLen) {
            var sum = 0.0
            for (j in 0 until halfLen) {
                if (j + tau < frameSize) {
                    val delta = buffer[j].toDouble() - buffer[j + tau].toDouble()
                    sum += delta * delta
                }
            }
            yinBuffer[tau] = sum.toFloat()
        }
    }

    private fun cumulativeMeanNormalisedDifference(halfLen: Int) {
        yinBuffer[0] = 1f
        var runningSum = 0f
        for (tau in 1 until halfLen) {
            runningSum += yinBuffer[tau]
            yinBuffer[tau] *= tau / runningSum
        }
    }

    private fun absoluteThreshold(halfLen: Int): Int? {
        val threshold = 0.15f
        for (tau in 2 until halfLen) {
            if (yinBuffer[tau] < threshold) {
                // Find local minimum
                while (tau + 1 < halfLen && yinBuffer[tau + 1] < yinBuffer[tau]) {
                    return tau + 1
                }
                return tau
            }
        }
        // No dip found — return index of global minimum as fallback
        return (2 until halfLen).minByOrNull { yinBuffer[it] }
    }

    private fun parabolicInterpolation(tau: Int, halfLen: Int): Float {
        val x0 = if (tau < 1) tau else tau - 1
        val x2 = if (tau + 1 < halfLen) tau + 1 else tau
        if (x0 == tau) return if (yinBuffer[tau] <= yinBuffer[x2]) tau.toFloat() else x2.toFloat()
        if (x2 == tau) return if (yinBuffer[tau] <= yinBuffer[x0]) tau.toFloat() else x0.toFloat()
        val s0 = yinBuffer[x0]; val s1 = yinBuffer[tau]; val s2 = yinBuffer[x2]
        return tau + (s2 - s0) / (2f * (2f * s1 - s2 - s0))
    }
}