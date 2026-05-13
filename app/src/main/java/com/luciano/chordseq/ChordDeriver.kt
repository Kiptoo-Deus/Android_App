package com.luciano.chordseq

// ═════════════════════════════════════════════════════════════════════════════
//  ChordDeriver.kt — Layer 2 Engine
//  ChordsPro · Luciano Muratore
//
//  Adapted from ChordDeriver.kt (com.example.chordpredictor) to fit
//  the ChordsPro architecture (com.luciano.chordseq).
//
//  Takes the 4-chord progression from ChordSeqAI + genre + decade
//  and derives 7 instrument-specific chord/note tracks.
//
//  No external libraries. No network calls. Pure Kotlin music theory.
//
//  Usage:
//    val result = ChordDeriver.deriveAllTracks(
//        chords = listOf("Cmaj7","Am7","Dm7","G7"),
//        genre  = "Jazz",
//        decade = "1960s"
//    )
// ═════════════════════════════════════════════════════════════════════════════

object ChordDeriver {

    data class TrackResult(
        val bass          : List<String>,
        val rhythmGuitar  : List<String>,
        val piano         : List<String>,
        val pads          : List<String>,
        val leadMelody    : List<String>,
        val counterMelody : List<String>,
        val percussion    : List<String>
    )

    fun deriveAllTracks(chords: List<String>, genre: String, decade: String): TrackResult {
        return TrackResult(
            bass          = chords.map { bassVoicing(it) },
            rhythmGuitar  = chords.map { rhythmGuitarVoicing(it, genre) },
            piano         = chords.map { pianoVoicing(it) },
            pads          = chords.map { padVoicing(it) },
            leadMelody    = chords.map { leadNote(it) },
            counterMelody = chords.map { counterNote(it) },
            percussion    = percussionPattern(genre, decade)
        )
    }

    private val NOTES = listOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")

    private fun extractRoot(chord: String): String {
        if (chord.length >= 2 && (chord[1] == '#' || chord[1] == 'b')) return chord.substring(0, 2)
        return chord.substring(0, 1)
    }

    private fun transpose(note: String, semitones: Int): String {
        val idx = NOTES.indexOf(note)
        if (idx < 0) return note
        return NOTES[((idx + semitones) % 12 + 12) % 12]
    }

    private fun isMinor(chord: String): Boolean {
        val s = chord.removePrefix(extractRoot(chord))
        return s.startsWith("m") && !s.startsWith("maj")
    }

    private fun isDominant7(chord: String): Boolean {
        val s = chord.removePrefix(extractRoot(chord))
        return s == "7" || s.startsWith("7")
    }

    private fun isMaj7(chord: String): Boolean {
        val s = chord.removePrefix(extractRoot(chord))
        return s.startsWith("maj7") || s.startsWith("M7")
    }

    private fun bassVoicing(chord: String) = "${extractRoot(chord)}2"

    private fun rhythmGuitarVoicing(chord: String, genre: String): String {
        val root = extractRoot(chord)
        return when (genre) {
            "Jazz", "Bossa Nova"           -> chord
            "Blues"                        -> "${root}7"
            "Rock"                         -> if (isMinor(chord)) "${root}m" else "${root}5"
            "Pop","Soul / R&B","Folk / Country" -> if (isMinor(chord)) "${root}m" else root
            "Funk"                         -> when {
                isMinor(chord)    -> "${root}m7"
                isDominant7(chord) -> "${root}9"
                else              -> "${root}7"
            }
            else -> chord
        }
    }

    private fun pianoVoicing(chord: String): String {
        val root    = extractRoot(chord)
        val fifth   = transpose(root, 7)
        val third   = if (isMinor(chord)) transpose(root, 3) else transpose(root, 4)
        val seventh = when {
            isMaj7(chord)      -> transpose(root, 11)
            isMinor(chord)     -> transpose(root, 10)
            isDominant7(chord) -> transpose(root, 10)
            else               -> transpose(root, 11)
        }
        return "LH:${root}3+${fifth}3  RH:${third}4+${seventh}4"
    }

    private fun padVoicing(chord: String): String {
        val root        = extractRoot(chord)
        val withoutRoot = chord.removePrefix(root)
        return root + when {
            withoutRoot.startsWith("maj7") -> withoutRoot.replace("maj7", "maj9")
            withoutRoot.startsWith("m7")   -> withoutRoot.replace("m7",   "m9")
            withoutRoot == "7"             -> "9"
            withoutRoot.startsWith("7")    -> withoutRoot.replace("7",    "9")
            withoutRoot == "m"             -> "m9"
            withoutRoot.isEmpty()          -> "add9"
            else                           -> "${withoutRoot}add9"
        }
    }

    private fun leadNote(chord: String): String {
        val root = extractRoot(chord)
        val note = when {
            isMaj7(chord)      -> transpose(root, 11)
            isMinor(chord)     -> transpose(root, 10)
            isDominant7(chord) -> transpose(root, 10)
            else               -> transpose(root, 7)
        }
        return "${note}5"
    }

    private fun counterNote(chord: String): String {
        val root = extractRoot(chord)
        val note = if (isMinor(chord)) transpose(root, 3) else transpose(root, 4)
        return "${note}4"
    }

    private fun percussionPattern(genre: String, decade: String): List<String> {
        return when (genre) {
            "Jazz" -> if (decade in listOf("1920s","1930s","1940s"))
                listOf("Swing kick: 1 & 3","Snare: 2 & 4","Hi-hat: 4","Ride: straight 4ths")
            else
                listOf("Ride: 1&2&3&4&","Snare: 2 & 4","Kick: beat 1","Hi-hat: beat 4")
            "Blues" ->
                listOf("Kick: 1 & 3","Snare: 2 & 4","Shuffle 8ths on hi-hat","Accent: beat 4")
            "Rock" -> when (decade) {
                "1950s","1960s" -> listOf("Kick: 1 & 3","Snare: 2 & 4","Hi-hat: 8ths","Accent: beat 2")
                "1980s","1990s" -> listOf("Kick: 1 & 3","Snare: 2 & 4 (gated)","Hi-hat: 16ths","Crash: beat 1")
                else            -> listOf("Kick: 1 & 3","Snare: 2 & 4","Hi-hat: 8ths","Ride: chorus")
            }
            "Pop"        -> listOf("Kick: 1 & 3","Snare: 2 & 4","Hi-hat: 16ths","Clap: 2 & 4")
            "Soul / R&B" -> listOf("Kick: 1 & 3e","Snare: 2 & 4","Hi-hat: 16ths","Ghost notes on snare")
            "Funk"       -> listOf("Kick: 1 & 3e &","Snare: 2 & 4","Hi-hat: 16ths tight","Ghost: snare 16ths")
            "Bossa Nova" -> listOf("Rimshot: 1 & 2+3 &","Kick: 1 & 3","No snare","Shaker: 8ths")
            "Folk / Country" -> listOf("Kick: 1 & 3","Snare: 2 & 4","Hi-hat: 8ths","Brush on snare")
            "Electronic" -> if (decade == "1980s")
                listOf("Kick: 1 & 3","Snare: 2 & 4","Hi-hat: 16ths","Synth perc: off-beats")
            else
                listOf("Kick: 4-on-floor","Snare/clap: 2 & 4","Hi-hat: 16ths","Perc: off-beats")
            else -> listOf("Kick: 1 & 3","Snare: 2 & 4","Hi-hat: 8ths","Accent: beat 3")
        }
    }
}