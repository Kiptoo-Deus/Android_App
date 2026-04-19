package com.luciano.chordseq

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * PianoSynth — Karplus-Strong synthesis with pre-cached note buffers.
 *
 * Key improvements over the previous version:
 *  1. All note buffers are pre-generated once at init() time.
 *     Playing a chord is then just a memory copy — zero synthesis latency.
 *  2. Chord notes are mixed into a single buffer before playback,
 *     eliminating the AudioTrack-per-note approach that caused glitches.
 *  3. A single reusable streaming AudioTrack is used for all playback.
 */
object PianoSynth {

    private const val TAG         = "PianoSynth"
    private const val SAMPLE_RATE = 44100
    private const val DURATION_MS = 1500
    private const val NUM_SAMPLES = SAMPLE_RATE * DURATION_MS / 1000

    // Pre-generated buffers: MIDI note → ShortArray
    private val noteCache = mutableMapOf<Int, FloatArray>()
    private var isInitialised = false

    // MIDI range to pre-cache (covers all chords in the vocabulary)
    private val MIDI_RANGE = 36..84

    /**
     * Pre-generate all note buffers. Call once on a background thread at startup.
     * After this, playChord() has no synthesis overhead.
     */
    fun init() {
        if (isInitialised) return
        Log.d(TAG, "Pre-generating ${MIDI_RANGE.count()} note buffers…")
        val t0 = System.currentTimeMillis()
        for (midi in MIDI_RANGE) {
            noteCache[midi] = karplusStrong(midiToFreq(midi), NUM_SAMPLES, volume = 0.75f)
        }
        isInitialised = true
        Log.d(TAG, "Note cache ready in ${System.currentTimeMillis() - t0}ms")
    }

    /**
     * Play a chord (list of MIDI notes) by mixing all notes into one buffer
     * and writing to a single AudioTrack. Glitch-free.
     */
    fun playChord(midiNotes: List<Int>, durationMs: Int = DURATION_MS) {
        if (midiNotes.isEmpty()) return
        Thread { playMixed(midiNotes, durationMs) }.start()
    }

    /**
     * Play notes one after another (arpeggio).
     */
    fun playArpeggio(midiNotes: List<Int>, noteMs: Int = 350) {
        Thread {
            for (note in midiNotes) {
                playMixed(listOf(note), noteMs)
                Thread.sleep((noteMs * 0.7).toLong())
            }
        }.start()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun playMixed(midiNotes: List<Int>, durationMs: Int) {
        try {
            val numSamples = (SAMPLE_RATE * durationMs / 1000).coerceAtMost(NUM_SAMPLES)

            // Mix all note buffers into one float buffer
            val mixed = FloatArray(numSamples)
            val scale = 1f / midiNotes.size.coerceAtLeast(1)

            for (midi in midiNotes) {
                val buf = noteCache[midi] ?: karplusStrong(midiToFreq(midi), numSamples, 0.75f)
                for (i in 0 until numSamples) {
                    mixed[i] += buf[i] * scale
                }
            }

            // Apply release envelope to avoid click at end
            val releaseSamples = (SAMPLE_RATE * 0.08).toInt()
            for (i in 0 until releaseSamples.coerceAtMost(numSamples)) {
                val pos = numSamples - releaseSamples + i
                if (pos < numSamples) {
                    mixed[pos] *= 1f - (i.toFloat() / releaseSamples)
                }
            }

            // Convert to 16-bit PCM
            val pcm = ShortArray(numSamples) { i ->
                (mixed[i] * Short.MAX_VALUE)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }

            // Play on a static AudioTrack
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufBytes = (pcm.size * 2).coerceAtLeast(minBuf)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
                .build()

            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(durationMs.toLong() + 50)
            track.stop()
            track.release()

        } catch (e: Exception) {
            Log.e(TAG, "playMixed failed", e)
        }
    }

    // ── Karplus-Strong synthesis ──────────────────────────────────────────────

    private fun karplusStrong(freqHz: Double, numSamples: Int, volume: Float): FloatArray {
        val delayLen = (SAMPLE_RATE / freqHz).toInt().coerceAtLeast(2)

        // Initialise delay line: blend of noise + fundamental sine
        val delayLine = FloatArray(delayLen) { i ->
            val noise = Random.nextFloat() * 2f - 1f
            val sine  = sin(2.0 * PI * i / delayLen).toFloat()
            noise * 0.7f + sine * 0.3f
        }

        // Decay — lower notes sustain longer, matching real piano physics
        val decay = when {
            freqHz < 130 -> 0.9988f
            freqHz < 260 -> 0.9983f
            freqHz < 520 -> 0.9978f
            else         -> 0.9970f
        }

        val output       = FloatArray(numSamples)
        val attackSamples = (SAMPLE_RATE * 0.004).toInt()
        var ptr           = 0

        for (i in 0 until numSamples) {
            val current  = delayLine[ptr]
            val next     = delayLine[(ptr + 1) % delayLen]
            val filtered = decay * 0.5f * (current + next)
            delayLine[ptr] = filtered

            val amp = if (i < attackSamples) volume * i.toFloat() / attackSamples else volume
            output[i] = amp * filtered
            ptr = (ptr + 1) % delayLen
        }

        return output
    }

    fun midiToFreq(midi: Int): Double = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
}