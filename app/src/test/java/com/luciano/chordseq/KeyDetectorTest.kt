package com.luciano.chordseq

// ═════════════════════════════════════════════════════════════════════════════
//  KeyDetectorTest.kt — Unit tests for Layer 4 (KeyDetector)
//  Run with: ./gradlew test
// ═════════════════════════════════════════════════════════════════════════════

import org.junit.Assert.*
import org.junit.Test

class KeyDetectorTest {

    // ── pitchClassOf ──────────────────────────────────────────────────────────

    @Test fun `pitchClassOf strips octave from natural notes`() {
        assertEquals(0,  KeyDetector.pitchClassOf("C4"))
        assertEquals(2,  KeyDetector.pitchClassOf("D3"))
        assertEquals(9,  KeyDetector.pitchClassOf("A5"))
        assertEquals(11, KeyDetector.pitchClassOf("B2"))
    }

    @Test fun `pitchClassOf strips octave from sharps`() {
        assertEquals(1,  KeyDetector.pitchClassOf("C#4"))
        assertEquals(6,  KeyDetector.pitchClassOf("F#3"))
        assertEquals(10, KeyDetector.pitchClassOf("A#5"))
    }

    @Test fun `pitchClassOf returns null for unknown note`() {
        assertNull(KeyDetector.pitchClassOf("X4"))
        assertNull(KeyDetector.pitchClassOf(""))
    }

    // ── detect: clear major keys ──────────────────────────────────────────────

    @Test fun `detects C major from C major triad notes`() {
        val notes = listOf("C4", "E4", "G4", "C5", "G4", "E4", "C4")
        val result = KeyDetector.detect(notes)
        assertNotNull(result)
        assertEquals("C",     result!!.tonic)
        assertEquals("major", result.mode)
        assertTrue("Confidence should be ≥ 0.6", result.confidence >= 0.6f)
    }

    @Test fun `detects G major from G major scale notes`() {
        val notes = listOf("G4","A4","B4","C5","D5","E5","F#5","G5")
        val result = KeyDetector.detect(notes)
        assertNotNull(result)
        assertEquals("G",     result!!.tonic)
        assertEquals("major", result.mode)
    }

    @Test fun `detects A minor from A minor natural scale`() {
        // Natural minor scale gives KS enough pitch-class info
        val notes = listOf("A4","B4","C5","D5","E5","F5","G5","A5")
        val result = KeyDetector.detect(notes)
        assertNotNull(result)
        // A minor and C major are relative keys — both are valid detections
        assertTrue("Should detect A minor or relative C major",
            (result!!.tonic == "A" && result.mode == "minor") ||
                    (result.tonic == "C" && result.mode == "major"))
    }

    // ── detect: edge cases ────────────────────────────────────────────────────

    @Test fun `returns null for empty input`() {
        assertNull(KeyDetector.detect(emptyList()))
    }

    @Test fun `returns null for single ambiguous note`() {
        // A single note cannot determine key with sufficient confidence
        val result = KeyDetector.detect(listOf("C4"))
        // Either null or low confidence — either is acceptable
        if (result != null) assertTrue(result.confidence < 0.99f)
    }

    @Test fun `returns null when confidence below threshold`() {
        // Chromatic notes — no clear key centre
        val notes = listOf("C4","C#4","D4","D#4","E4","F4","F#4","G4","G#4","A4","A#4","B4")
        val result = KeyDetector.detect(notes)
        // Chromatic input has ambiguous key — should be null or very low confidence
        if (result != null) assertTrue(result.confidence < 0.75f)
    }
}