package com.luciano.chordseq

// ═════════════════════════════════════════════════════════════════════════════
//  KeyDetector.kt — Layer 4: Key Detection (Tonality Inference)
//  ChordAnds · Luciano Muratore
// ═════════════════════════════════════════════════════════════════════════════
//
//  LAYER 4 — KEY DETECTION
//  ┌─────────────────────────────────────────────────────────────────────────┐
//  │  Algorithm : Krumhansl-Schmuckler (1990)                               │
//  │  Input     : List of note names from AudioCapture (Layers 2 & 3)      │
//  │              e.g. ["C4", "E4", "G4", "D4"]                            │
//  │  Output    : KeyResult(tonic, mode, confidence) or null               │
//  │                                                                         │
//  │  Steps:                                                                 │
//  │  1. Strip octave numbers → pitch classes (C, D, E … B)                │
//  │  2. Build a 12-element frequency vector (how often each pitch class    │
//  │     appears in the input)                                              │
//  │  3. Correlate the frequency vector against the 24 Krumhansl-          │
//  │     Schmuckler major and minor key profiles using Pearson correlation  │
//  │  4. Return the best matching key                                        │
//  │  5. If best confidence < 0.6 → return null (ask user to hum more)     │
//  └─────────────────────────────────────────────────────────────────────────┘
//
//  HOW IT CONNECTS TO OTHER LAYERS:
//  AudioCapture (L2+L3) → List<String> notes → KeyDetector.detect() →
//  KeyResult → HarmonyEngine (L5) → chord progression prediction
//
//  UNIT TESTS: see KeyDetectorTest.kt
//
// ═════════════════════════════════════════════════════════════════════════════

// ─────────────────────────────────────────────────────────────────────────────
//  Result type
// ─────────────────────────────────────────────────────────────────────────────
data class KeyResult(
    val tonic     : String,   // e.g. "C", "A#"
    val mode      : String,   // "major" or "minor"
    val confidence: Float     // Pearson r, 0.0–1.0
)

// ─────────────────────────────────────────────────────────────────────────────
//  KeyDetector
// ─────────────────────────────────────────────────────────────────────────────
object KeyDetector {

    // ── Krumhansl-Schmuckler profiles ─────────────────────────────────────────
    // Source: Krumhansl, C.L. (1990). Cognitive Foundations of Musical Pitch.
    // Oxford University Press. Values represent perceived stability of each
    // pitch class within a key (tonic = index 0).

    private val MAJOR_PROFILE = doubleArrayOf(
        6.35, 2.23, 3.48, 2.33, 4.38, 4.09,
        2.52, 5.19, 2.39, 3.66, 2.29, 2.88
    )

    private val MINOR_PROFILE = doubleArrayOf(
        6.33, 2.68, 3.52, 5.38, 2.60, 3.53,
        2.54, 4.75, 3.98, 2.69, 3.34, 3.17
    )

    private val NOTE_NAMES = listOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Infer the most likely musical key from a list of note names.
     *
     * @param notes  e.g. ["C4", "E4", "G4", "D4", "F4"]
     * @return       KeyResult if confidence ≥ 0.6, null otherwise
     *
     * Examples:
     *   detect(["C4","E4","G4","B4","D4"]) → KeyResult("C", "major", 0.91f)
     *   detect(["A4","C4","E4","G4"])      → KeyResult("A", "minor", 0.87f)
     */
    fun detect(notes: List<String>): KeyResult? {
        if (notes.isEmpty()) return null

        // Step 1 — Strip octave numbers → pitch class indices
        val pitchClasses = notes.mapNotNull { pitchClassOf(it) }
        if (pitchClasses.isEmpty()) return null

        // Step 2 — Build frequency vector
        val freq = DoubleArray(12)
        pitchClasses.forEach { freq[it]++ }

        // Step 3 — Correlate against all 24 key profiles
        var bestKey        = ""
        var bestMode       = ""
        var bestCorrelation = Double.NEGATIVE_INFINITY

        for (tonic in 0..11) {
            // Rotate profiles so that index 0 = current tonic
            val majorCorr = pearson(freq, rotate(MAJOR_PROFILE, tonic))
            val minorCorr = pearson(freq, rotate(MINOR_PROFILE, tonic))

            if (majorCorr > bestCorrelation) {
                bestCorrelation = majorCorr
                bestKey  = NOTE_NAMES[tonic]
                bestMode = "major"
            }
            if (minorCorr > bestCorrelation) {
                bestCorrelation = minorCorr
                bestKey  = NOTE_NAMES[tonic]
                bestMode = "minor"
            }
        }

        // Step 4 — Normalise correlation to 0..1 range (Pearson is -1..1)
        val confidence = ((bestCorrelation + 1.0) / 2.0).toFloat()

        // Step 5 — Reject if below confidence threshold
        if (confidence < 0.6f) return null

        return KeyResult(bestKey, bestMode, confidence)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Strips octave number and returns pitch class index (0=C … 11=B).
     * e.g. "C4" → 0,  "A#3" → 10,  "F#5" → 6
     * Returns null if the note name is unrecognised.
     */
    fun pitchClassOf(noteName: String): Int? {
        // Remove trailing digit(s) for octave
        val stripped = noteName.trimEnd { it.isDigit() || it == '-' }
        return NOTE_NAMES.indexOf(stripped).takeIf { it >= 0 }
    }

    /**
     * Rotates an array left by [n] positions.
     * Used to align the profile with the current tonic.
     */
    private fun rotate(arr: DoubleArray, n: Int): DoubleArray {
        val size = arr.size
        val shift = n % size
        return DoubleArray(size) { arr[(it + shift) % size] }
    }

    /**
     * Pearson correlation coefficient between two equal-length vectors.
     * Returns a value between -1.0 and 1.0.
     */
    private fun pearson(x: DoubleArray, y: DoubleArray): Double {
        val mx   = x.average()
        val my   = y.average()
        var num  = 0.0; var dx2 = 0.0; var dy2 = 0.0
        for (i in x.indices) {
            val dx = x[i] - mx; val dy = y[i] - my
            num += dx * dy; dx2 += dx * dx; dy2 += dy * dy
        }
        val denom = Math.sqrt(dx2 * dy2)
        return if (denom == 0.0) 0.0 else num / denom
    }
}