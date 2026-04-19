package com.luciano.chordseq

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager

class ChordMidiPlayer(private val context: Context) {

    private var chordToNotes: Map<String, List<Int>> = emptyMap()

    init {
        loadNoteMapping()
    }

    private fun loadNoteMapping() {
        val jsonString = context.assets.open("chord_to_notes.json").bufferedReader().use { it.readText() }
        val mapType = object : TypeToken<Map<String, List<Int>>>() {}.type
        chordToNotes = Gson().fromJson(jsonString, mapType)
    }

    fun getNotesForChord(chordName: String): List<Int> {
        // Look up the MIDI notes. If the AI suggests "Cmaj7", 
        // this returns [48, 52, 55, 59]
        return chordToNotes[chordName] ?: emptyList()
    }
    
    // Note: To actually play sound, you'll need an Android Synthesizer.
    // For now, this acts as the data provider for the UI or Audio Engine.
}