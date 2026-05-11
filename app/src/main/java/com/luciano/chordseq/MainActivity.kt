package com.luciano.chordseq

import android.app.AlertDialog
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import android.os.Build
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────────────────────
//  Colour palette
// ─────────────────────────────────────────────────────────────────────────────
private object C {
    val BG_SCREEN   = Color.parseColor("#0E0E14")
    val BG_WRAP     = Color.parseColor("#1A1A24")
    val BG_SECTION  = Color.parseColor("#111120")
    val BG_CARD     = Color.parseColor("#161628")
    val BG_PREDICT  = Color.parseColor("#0A0A12")
    val BORDER      = Color.parseColor("#2A2A3E")

    val TXT_PRIMARY = Color.parseColor("#E8E8F2")
    val TXT_MUTED   = Color.parseColor("#555570")
    val TXT_HINT    = Color.parseColor("#444466")

    val PURPLE      = Color.parseColor("#534AB7")
    val PURPLE_MID  = Color.parseColor("#7F77DD")
    val PURPLE_DARK = Color.parseColor("#3C3489")
    val PURPLE_LITE = Color.parseColor("#CECBF6")
    val ORANGE      = Color.parseColor("#FF6A4A")

    val SLOTS = arrayOf(
        intArrayOf(Color.parseColor("#18153A"), Color.parseColor("#CCC8FF"),
            Color.parseColor("#7F77DD"), Color.parseColor("#7F77DD")),
        intArrayOf(Color.parseColor("#0D1E18"), Color.parseColor("#9FE1CB"),
            Color.parseColor("#1D9E75"), Color.parseColor("#1D9E75")),
        intArrayOf(Color.parseColor("#1E1508"), Color.parseColor("#FAC775"),
            Color.parseColor("#BA7517"), Color.parseColor("#BA7517")),
        intArrayOf(Color.parseColor("#1E0D14"), Color.parseColor("#F4C0D1"),
            Color.parseColor("#D4537E"), Color.parseColor("#D4537E"))
    )
    val NOTE_COLORS = intArrayOf(
        Color.parseColor("#7F77DD"), Color.parseColor("#1D9E75"),
        Color.parseColor("#BA7517"), Color.parseColor("#D4537E")
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Data model — mutable note grid
//  Each chord slot holds a mutable list of MIDI notes the user can edit.
//  We keep this separate from ChordEngine.Prediction so edits don't need
//  a full re-prediction.
// ─────────────────────────────────────────────────────────────────────────────
data class EditableChord(
    var name      : String,           // display name, re-analysed on edit
    val midiNotes : MutableList<Int>  // live note list
)

// ─────────────────────────────────────────────────────────────────────────────
//  Chord analyser — derives a chord name from a set of MIDI notes.
//  Uses a simple interval-pattern lookup. Returns "?" for unknown combos.
// ─────────────────────────────────────────────────────────────────────────────
object ChordAnalyser {

    private val NOTE_NAMES = listOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")

    // Interval patterns → chord suffix
    private val PATTERNS = listOf(
        setOf(0,4,7)     to "",
        setOf(0,3,7)     to "m",
        setOf(0,4,7,11)  to "maj7",
        setOf(0,4,7,10)  to "7",
        setOf(0,3,7,10)  to "m7",
        setOf(0,3,6)     to "dim",
        setOf(0,4,8)     to "aug",
        setOf(0,5,7)     to "sus4",
        setOf(0,2,7)     to "sus2",
        setOf(0,4,7,9)   to "6",
        setOf(0,3,7,9)   to "m6",
        setOf(0,4,7,10,14) to "9",
        setOf(0,3,7,10,14) to "m9",
        setOf(0,4,7,10,13) to "7b9",
        setOf(0,3,6,10)  to "m7b5",
        setOf(0,4,6,10)  to "7b5",
        setOf(0,4,7,10,17) to "11",
        setOf(0,3,7,10,17) to "m11"
    )

    fun analyse(midiNotes: List<Int>): String {
        if (midiNotes.isEmpty()) return "—"
        if (midiNotes.size == 1) return NOTE_NAMES[midiNotes[0] % 12]

        // Try every note as potential root
        val pcs = midiNotes.map { it % 12 }.toSet()
        for (root in pcs) {
            val intervals = pcs.map { ((it - root + 12) % 12) }.toSet()
            val match = PATTERNS.firstOrNull { it.first == intervals }
            if (match != null) return NOTE_NAMES[root] + match.second
        }
        // Partial match — just name root + note count
        val root = midiNotes.minOrNull()!! % 12
        return NOTE_NAMES[root] + "(custom)"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  MainActivity
// ─────────────────────────────────────────────────────────────────────────────
class MainActivity : AppCompatActivity() {

    private lateinit var chordEngine : ChordEngine
    private val editableChords       = mutableListOf<EditableChord>()   // live editable grid
    private val tokenHistory         = mutableListOf<Long>()
    private val inferenceTimes       = mutableListOf<Long>()

    private val temperatures = listOf(0.7f to "Safe", 1.0f to "Balanced", 1.3f to "Creative")
    private var tempIndex      = 1
    private var selectedGenre  = -1
    private var selectedDecade = -1
    private var selectedRoot   = "A#"
    private var chordsByRoot   = mapOf<String, List<String>>()
    private var selectedChordName: String? = null

    // Playback
    private var isPlaying = false
    private var playJob   : Job? = null
    private var bpm       = 120
    private var snapValue = "1/16"

    // UI refs
    private lateinit var metaText      : TextView
    private lateinit var statusBadge   : TextView
    private lateinit var genreBtn      : TextView
    private lateinit var decadeBtn     : TextView
    private lateinit var pianoSelector : PianoSelectorView
    private lateinit var chordHint     : TextView
    private lateinit var timeline      : LinearLayout
    private lateinit var rollTitleText : TextView
    private lateinit var pianoRollView : PianoRollView
    private lateinit var velocityView  : VelocityView

    // ── 7-track section refs ──────────────────────────────────────────────────
    private lateinit var tracksSection   : LinearLayout
    private lateinit var tvBass          : TextView
    private lateinit var tvRhythmGuitar  : TextView
    private lateinit var tvPiano         : TextView
    private lateinit var tvPads          : TextView
    private lateinit var tvLead          : TextView
    private lateinit var tvCounter       : TextView
    private lateinit var tvPercussion    : TextView
    private lateinit var predictBtn    : TextView
    private lateinit var playBtn       : TextView
    private lateinit var generateBtn   : TextView
    private lateinit var bpmLabel      : TextView
    private lateinit var snapBadge     : TextView

    // Recording state
    private lateinit var audioCapture   : AudioCapture
    private var isRecording             = false
    private lateinit var micBtn         : TextView
    private lateinit var waveformBtn    : WaveformButtonView
    private lateinit var confirmBtn     : TextView
    private lateinit var cancelRecBtn   : TextView
    private lateinit var liveNoteText   : TextView

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(C.BG_WRAP)
        buildUI()
        // Init audio capture
        audioCapture = AudioCapture(
            context = this,
            onNoteDetected = { name, midi -> onLiveNoteDetected(name, midi) },
            onPermissionDenied = { chordHint.text = "Microphone permission denied" }
        )
        // Request mic permission upfront
        if (!audioCapture.hasPermission()) {
            ActivityCompat.requestPermissions(this,
                arrayOf(android.Manifest.permission.RECORD_AUDIO), 101)
        }

        chordEngine = ChordEngine(this)

        // Start MIDI — background, no UI needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ConnectMidi.start(this, ::onMidiEvent)
        }

        lifecycleScope.launch {
            statusBadge.text = "Loading model…"
            val engineJob = async(Dispatchers.IO) { chordEngine.load() }
            val synthJob  = async(Dispatchers.IO) { PianoSynth.init() }
            try {
                engineJob.await(); synthJob.await()
                onEngineReady()
            } catch (e: Exception) {
                Log.e("ChordsPro", "Startup failed", e)
                statusBadge.text = "Error loading model"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        playJob?.cancel()
        if (::chordEngine.isInitialized) chordEngine.close()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ConnectMidi.stop()
        }
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private fun buildUI() {
        val scroll = ScrollView(this).apply { setBackgroundColor(C.BG_WRAP) }
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG_SCREEN)
        }
        screen.addView(buildTopBar()); screen.addView(hDivider())
        screen.addView(buildStyleSection()); screen.addView(hDivider())
        screen.addView(buildPianoSection()); screen.addView(hDivider())
        screen.addView(buildProgressionSection()); screen.addView(hDivider())
        screen.addView(buildRollHeader())
        screen.addView(buildRollBody()); screen.addView(hDivider())
        screen.addView(buildVelocitySection()); screen.addView(hDivider())
        screen.addView(buildTracksSection()); screen.addView(hDivider())
        screen.addView(buildBottomBar())
        scroll.addView(screen); setContentView(scroll)
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(10)); gravity = Gravity.CENTER_VERTICAL
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = "ChordsPro"; textSize = 16f
            setTypeface(null, Typeface.BOLD); setTextColor(C.TXT_PRIMARY)
        })
        metaText = TextView(this).apply {
            text = "Select genre, decade & first chord"; textSize = 10f; setTextColor(C.TXT_MUTED)
        }
        statusBadge = TextView(this).apply { text = "Starting…"; textSize = 9f; setTextColor(C.TXT_HINT) }
        col.addView(metaText); col.addView(statusBadge)
        bar.addView(col, lp(0, WRAP) { weight = 1f })
        val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        C.SLOTS.forEach { cols ->
            dots.addView(View(this).apply {
                setBackgroundColor(cols[3])
                layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginStart = dp(5) }
            })
        }
        bar.addView(dots); return bar
    }

    private fun buildStyleSection(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(secLabel("① style settings"))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), 0, dp(14), dp(12))
        }
        genreBtn  = pickerCard("Genre",  "None").also { it.setOnClickListener { showPicker("Genre")  } }
        decadeBtn = pickerCard("Decade", "None").also { it.setOnClickListener { showPicker("Decade") } }
        row.addView(genreBtn,  lp(0, WRAP) { weight = 1f; marginEnd = dp(8) })
        row.addView(decadeBtn, lp(0, WRAP) { weight = 1f })
        col.addView(row); return col
    }

    private fun buildPianoSection(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(secLabel("② starting chord"))
        pianoSelector = PianoSelectorView(this) { root ->
            selectedRoot = root; updateChordForRoot(); updateMeta()
            val name  = selectedChordName ?: root
            val notes = if (::chordEngine.isInitialized) chordEngine.notesForChord(name).toMutableList() else mutableListOf()
            chordHint.text = "$name · tap Predict to build progression"
            if (editableChords.isEmpty()) {
                pianoRollView.setPreviewChord(EditableChord(name, notes))
            } else {
                editableChords[0] = EditableChord(name, notes)
                pianoRollView.setEditableChords(editableChords)
                rebuildTimeline()
            }
            if (notes.isNotEmpty()) PianoSynth.playChord(notes)
        }
        col.addView(pianoSelector, lp(MATCH, dp(72)) { setMargins(dp(14), 0, dp(14), dp(6)) })
        chordHint = TextView(this).apply {
            text = "tap a key to set the first chord"
            textSize = 10f; setTextColor(C.PURPLE_MID); gravity = Gravity.CENTER
        }
        col.addView(chordHint, lp(MATCH, WRAP) { setMargins(dp(14), 0, dp(14), dp(10)) })
        return col
    }

    private fun buildProgressionSection(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(secLabel("③ chord progression"))
        timeline = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(C.BG_SECTION)
        }
        rebuildTimeline()
        col.addView(timeline, lp(MATCH, dp(66)) { setMargins(dp(14), 0, dp(14), dp(10)) })
        return col
    }

    private fun buildRollHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(4)); gravity = Gravity.CENTER_VERTICAL
        }
        rollTitleText = TextView(this).apply { text = "piano roll · tap=add  drag=move  hold=delete"; textSize = 9f; setTextColor(C.TXT_HINT) }
        row.addView(rollTitleText, lp(0, WRAP) { weight = 1f })
        snapBadge = smallBadge(snapValue, active = true)
        snapBadge.setOnClickListener { showSnapPicker() }
        row.addView(snapBadge)
        row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) })
        row.addView(smallBadge("snap"))
        return row
    }

    private fun buildRollBody(): View {
        pianoRollView = PianoRollView(this,
            onNoteAdded   = { chordIdx, midi -> onNoteAdded(chordIdx, midi) },
            onNoteRemoved = { chordIdx, midi -> onNoteRemoved(chordIdx, midi) },
            onNoteMoved   = { fromChord, toChord, midi -> onNoteMoved(fromChord, toChord, midi) }
        )
        return pianoRollView.also {
            it.layoutParams = LinearLayout.LayoutParams(MATCH, dp(220)).apply {
                setMargins(dp(14), 0, dp(14), dp(8))
            }
        }
    }

    private fun buildVelocitySection(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        col.addView(TextView(this).apply { text = "velocity"; textSize = 10f; setTextColor(C.TXT_HINT) })
        velocityView = VelocityView(this)
        col.addView(velocityView, LinearLayout.LayoutParams(MATCH, dp(22)).apply { topMargin = dp(4) })
        return col
    }

    private fun buildTracksSection(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "tracks_section"
        }
        col.addView(secLabel("④ instrument tracks"))

        val instruments = listOf(
            "Bass"           to "root · oct 2",
            "Rhythm Guitar"  to "genre voicing",
            "Piano"          to "split LH / RH",
            "Pads / Strings" to "extended chord",
            "Lead Melody"    to "7th · oct 5",
            "Countermelody"  to "3rd · oct 4",
            "Percussion"     to "pattern guide"
        )
        val colors = listOf(
            C.SLOTS[0], C.SLOTS[1], C.SLOTS[2], C.SLOTS[3],
            C.SLOTS[0], C.SLOTS[1], C.SLOTS[2]
        )

        val tvRefs = mutableListOf<TextView>()
        instruments.forEachIndexed { i, (name, rule) ->
            val cols = colors[i]
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(cols[0])
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            // Header row: instrument name + rule
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(TextView(this).apply {
                text = name; textSize = 12f
                setTypeface(null, Typeface.BOLD); setTextColor(cols[1])
            }, lp(0, WRAP) { weight = 1f })
            header.addView(TextView(this).apply {
                text = rule; textSize = 9f; setTextColor(cols[2])
            })
            card.addView(header)

            // Accent bar
            card.addView(View(this).apply {
                setBackgroundColor(cols[3])
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(2)).apply { topMargin = dp(6); bottomMargin = dp(6) }
            })

            // Output text
            val tv = TextView(this).apply {
                text = "—"; textSize = 11f; setTextColor(cols[1])
                typeface = Typeface.MONOSPACE
                alpha = 0.6f
            }
            card.addView(tv)
            tvRefs.add(tv)

            col.addView(card, lp(MATCH, WRAP) { setMargins(dp(14), 0, dp(14), dp(6)) })
        }

        tvBass         = tvRefs[0]
        tvRhythmGuitar = tvRefs[1]
        tvPiano        = tvRefs[2]
        tvPads         = tvRefs[3]
        tvLead         = tvRefs[4]
        tvCounter      = tvRefs[5]
        tvPercussion   = tvRefs[6]
        tracksSection  = col

        col.visibility = View.GONE
        return col
    }

    private fun buildBottomBar(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ── Row 1: Play | Mic | BPM | Generate ───────────────────────────────
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(10), dp(14), dp(6)); gravity = Gravity.CENTER_VERTICAL
        }

        playBtn = iconBtn("▶") { togglePlay() }

        // Mic button — tap to start recording
        micBtn = TextView(this).apply {
            text = "🎤"; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(C.TXT_MUTED); setBackgroundColor(C.BG_CARD)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            minWidth = dp(40); minimumHeight = dp(36)
            setOnClickListener { startRecording() }
        }

        // Animated waveform — shown while recording (hidden initially)
        waveformBtn = WaveformButtonView(this).apply {
            visibility = View.GONE
        }

        // Live note text — shows detected note while recording
        liveNoteText = TextView(this).apply {
            text = ""; textSize = 11f; setTextColor(C.PURPLE_MID)
            gravity = Gravity.CENTER; visibility = View.GONE
        }

        bpmLabel = TextView(this).apply {
            text = "$bpm bpm"; textSize = 11f; setTextColor(C.TXT_MUTED)
            setOnClickListener { showBpmPicker() }
        }

        generateBtn = pillBtn("Generate ↗", primary = true) { onGenerateClicked() }
        generateBtn.isEnabled = false; generateBtn.alpha = 0.38f

        // Confirm (✓) and Cancel (✕) — shown only during recording
        confirmBtn = pillBtn("✓", primary = true) { confirmRecording() }.apply {
            visibility = View.GONE; textSize = 16f
        }
        cancelRecBtn = iconBtn("✕") { cancelRecording() }.apply {
            visibility = View.GONE
        }

        row1.addView(playBtn)
        row1.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
        row1.addView(micBtn)
        row1.addView(waveformBtn, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(4) })
        row1.addView(liveNoteText, lp(0, WRAP) { weight = 1f; marginStart = dp(8) })
        row1.addView(bpmLabel, lp(0, WRAP) { weight = 1f })
        row1.addView(cancelRecBtn)
        row1.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) })
        row1.addView(confirmBtn)
        row1.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) })
        row1.addView(generateBtn)
        col.addView(row1)

        // ── Row 2: Predict | Reset ────────────────────────────────────────────
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), 0, dp(14), dp(14)); gravity = Gravity.CENTER_VERTICAL
        }
        predictBtn = pillBtn("Predict next ↗", primary = false) { onPredictClicked() }
        predictBtn.isEnabled = false; predictBtn.alpha = 0.38f
        val resetBtn = iconBtn("↺") { onResetClicked() }
        row2.addView(predictBtn, lp(0, WRAP) { weight = 1f; marginEnd = dp(8) })
        row2.addView(resetBtn)
        col.addView(row2); return col
    }

    // ── Timeline ──────────────────────────────────────────────────────────────

    private fun rebuildTimeline() {
        timeline.removeAllViews()
        for (i in 0..3) {
            val chord = editableChords.getOrNull(i)
            val view = when {
                chord != null             -> filledSlot(i, chord)
                i == editableChords.size  -> predictSlot()
                else                      -> emptySlot(i)
            }
            timeline.addView(view, lp(0, MATCH) { weight = 1f })
        }
    }

    private fun filledSlot(i: Int, chord: EditableChord): View {
        val cols = C.SLOTS[i % C.SLOTS.size]
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(7), dp(8), 0); setBackgroundColor(cols[0])
            addView(TextView(this@MainActivity).apply {
                text = chord.name; textSize = 13f
                setTypeface(null, Typeface.BOLD); setTextColor(cols[1])
            })
            addView(TextView(this@MainActivity).apply {
                text = "${romanNumeral(i)} · ${if (chord.midiNotes.isEmpty()) "empty" else "custom"}"; textSize = 9f; setTextColor(cols[2])
            })
            addView(View(this@MainActivity).apply {
                setBackgroundColor(cols[3])
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(3)).apply { topMargin = dp(5) }
            })
            setOnClickListener { PianoSynth.playChord(chord.midiNotes) }
        }
    }

    private fun predictSlot(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(C.BG_PREDICT)
            addView(TextView(this@MainActivity).apply {
                text = "+"; textSize = 18f; gravity = Gravity.CENTER
                setTextColor(C.PURPLE_MID); setPadding(dp(8), dp(2), dp(8), dp(2))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT); setStroke(dp(1), C.PURPLE_MID)
                }
            })
            addView(TextView(this@MainActivity).apply {
                text = "predict\nnext"; textSize = 8f
                setTextColor(C.TXT_HINT); gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0)
            })
            setOnClickListener { if (predictBtn.isEnabled) onPredictClicked() }
        }
    }

    private fun emptySlot(i: Int): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(C.BG_SECTION); alpha = 0.30f
            addView(TextView(this@MainActivity).apply {
                text = "chord ${i + 1}"; textSize = 9f
                setTextColor(C.TXT_HINT); gravity = Gravity.CENTER
            })
        }
    }

    // ── Note edit callbacks from PianoRollView ────────────────────────────────

    /** User tapped an empty cell → add a note */
    private fun onNoteAdded(chordIdx: Int, midi: Int) {
        val chord = editableChords.getOrNull(chordIdx) ?: return
        if (!chord.midiNotes.contains(midi)) {
            chord.midiNotes.add(midi)
            chord.midiNotes.sort()
            reanalyseChord(chordIdx)
            PianoSynth.playChord(listOf(midi))
        }
    }

    /** User long-pressed a note → remove it */
    private fun onNoteRemoved(chordIdx: Int, midi: Int) {
        val chord = editableChords.getOrNull(chordIdx) ?: return
        chord.midiNotes.remove(midi)
        reanalyseChord(chordIdx)
        if (chord.midiNotes.isNotEmpty()) PianoSynth.playChord(chord.midiNotes)
    }

    /** User dragged a note from one chord column to another (or within same chord to different pitch) */
    private fun onNoteMoved(fromChordIdx: Int, toChordIdx: Int, midi: Int) {
        val from = editableChords.getOrNull(fromChordIdx) ?: return
        val to   = editableChords.getOrNull(toChordIdx)   ?: return
        from.midiNotes.remove(midi)
        if (!to.midiNotes.contains(midi)) to.midiNotes.add(midi)
        to.midiNotes.sort()
        reanalyseChord(fromChordIdx)
        reanalyseChord(toChordIdx)
        PianoSynth.playChord(to.midiNotes)
    }

    /** Re-derives chord name from current notes and refreshes the timeline slot */
    private fun reanalyseChord(idx: Int) {
        val chord = editableChords.getOrNull(idx) ?: return
        chord.name = ChordAnalyser.analyse(chord.midiNotes)
        pianoRollView.setEditableChords(editableChords)
        rebuildTimeline()
        chordHint.text = "Chord ${idx + 1} → ${chord.name}"
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    private fun playProgression() {
        playJob?.cancel()
        val chordDurationMs = (60_000L / bpm) * 4
        playJob = lifecycleScope.launch {
            setBtnEnabled(generateBtn, false); playBtn.text = "⏸"
            val total = editableChords.size
            for ((i, chord) in editableChords.withIndex()) {
                if (!kotlinx.coroutines.currentCoroutineContext().isActive) break
                highlightTimelineSlot(i)
                pianoRollView.startPlayhead(i, total, chordDurationMs)
                velocityView.animateForChord(i, chordDurationMs)
                PianoSynth.playChord(chord.midiNotes, durationMs = chordDurationMs.toInt())
                delay(chordDurationMs)
            }
            withContext(Dispatchers.Main) {
                isPlaying = false; playBtn.text = "▶"
                setBtnEnabled(generateBtn, editableChords.size >= 2)
                pianoRollView.stopPlayhead(); velocityView.stopAnimation(); rebuildTimeline()
            }
        }
    }

    private fun highlightTimelineSlot(activeIdx: Int) {
        for (i in 0 until timeline.childCount) {
            timeline.getChildAt(i)?.alpha = if (i == activeIdx) 1f else 0.45f
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun onPredictClicked() {
        if (editableChords.size >= 4) return

        if (editableChords.isEmpty()) {
            val name = selectedChordName ?: run { chordHint.text = "Pick a starting note first"; return }
            val tokenId = chordEngine.tokenForChord(name)
                ?: chordsByRoot[selectedRoot]?.firstOrNull()?.let { chordEngine.tokenForChord(it) }
                ?: run { chordHint.text = "'$name' not in model vocabulary"; return }
            val notes = chordEngine.notesForChord(name).toMutableList()
            tokenHistory.clear(); tokenHistory.add(ChordEngine.BOS_TOKEN)
            tokenHistory.add(tokenId.toLong())
            addEditableChord(EditableChord(name, notes))
            PianoSynth.playChord(notes)
            if (editableChords.size >= 4) return
        }

        setBtnEnabled(predictBtn, false); predictBtn.text = "Thinking…"
        val genreW  = FloatArray(ChordEngine.N_GENRES).also  { w -> if (selectedGenre  in 0 until ChordEngine.N_GENRES)  w[selectedGenre]  = 1f }
        val decadeW = FloatArray(ChordEngine.N_DECADES).also { w -> if (selectedDecade in 0 until ChordEngine.N_DECADES) w[selectedDecade] = 1f }

        lifecycleScope.launch(Dispatchers.IO) {
            val pred = runCatching {
                chordEngine.predictNextChord(
                    inputIds      = tokenHistory,
                    genreWeights  = genreW,
                    decadeWeights = decadeW,
                    temperature   = temperatures[tempIndex].first
                )
            }.onFailure { Log.e("ChordsPro", "Prediction failed", it) }.getOrNull()

            withContext(Dispatchers.Main) {
                if (pred != null) {
                    inferenceTimes.add(pred.inferenceMs)
                    addEditableChord(EditableChord(pred.chordName, pred.midiNotes.toMutableList()))
                    PianoSynth.playChord(pred.midiNotes)
                    rollTitleText.text = "piano roll · tap=add  drag=move  hold=delete"
                }
                predictBtn.text = "Predict next ↗"
                setBtnEnabled(predictBtn, editableChords.size < 4)
                setBtnEnabled(generateBtn, editableChords.size >= 2)
            }
        }
    }

    private fun onGenerateClicked() {
        if (editableChords.isEmpty()) return
        isPlaying = true; playProgression()
    }

    private fun togglePlay() {
        if (editableChords.isEmpty()) return
        isPlaying = !isPlaying
        if (isPlaying) {
            playProgression()
        } else {
            playJob?.cancel(); playBtn.text = "▶"
            setBtnEnabled(generateBtn, editableChords.size >= 2)
            pianoRollView.stopPlayhead(); velocityView.stopAnimation(); rebuildTimeline()
        }
    }

    private fun onResetClicked() {
        playJob?.cancel(); isPlaying = false; playBtn.text = "▶"
        tokenHistory.clear(); tokenHistory.add(ChordEngine.BOS_TOKEN)
        editableChords.clear(); inferenceTimes.clear()
        rebuildTimeline()
        pianoRollView.setEditableChords(emptyList()); pianoRollView.stopPlayhead()
        velocityView.stopAnimation()
        rollTitleText.text = "piano roll · tap=add  drag=move  hold=delete"
        chordHint.text     = "tap a key to set the first chord"
        setBtnEnabled(predictBtn, true); setBtnEnabled(generateBtn, false)
        hideTracks()
        updateMeta()
    }

    private fun addEditableChord(chord: EditableChord) {
        if (editableChords.size >= 4) return
        editableChords.add(chord)
        pianoRollView.setEditableChords(editableChords)
        rebuildTimeline()
        val rem = 4 - editableChords.size
        chordHint.text = "${chord.name} added · $rem slot${if (rem != 1) "s" else ""} remaining"
        setBtnEnabled(predictBtn, editableChords.size < 4)
        setBtnEnabled(generateBtn, editableChords.size >= 2)

        // Show 7-track breakdown as soon as all 4 chords are ready
        if (editableChords.size == 4) {
            val genre  = if (selectedGenre  >= 0) chordEngine.genreLabels.getOrElse(selectedGenre)  { "" } else ""
            val decade = if (selectedDecade >= 0) ChordEngine.DECADE_LABELS.getOrElse(selectedDecade) { "" } else ""
            displaySevenTracks(editableChords.map { it.name }, genre, decade)
        }
    }

    // ── MIDI event handler ───────────────────────────────────────────────────

    private fun onMidiEvent(event: MidiEvent) {
        when (event) {
            is MidiEvent.DeviceConnected -> {
                statusBadge.text = "${event.type} MIDI: ${event.name}"
                Log.d("ChordsPro", "MIDI connected: ${event.name} (${event.type})")
            }
            is MidiEvent.DeviceDisconnected -> {
                statusBadge.text = "MIDI disconnected"
            }
            is MidiEvent.NoteOn -> {
                // Add note to the active chord slot on the piano roll
                val targetSlot = editableChords.size.coerceAtMost(3)
                if (editableChords.isNotEmpty()) {
                    val lastIdx = editableChords.size - 1
                    onNoteAdded(lastIdx, event.note)
                } else {
                    // No chords yet — use the note as a preview on the piano roll
                    val preview = EditableChord(
                        ChordAnalyser.analyse(listOf(event.note)),
                        mutableListOf(event.note)
                    )
                    pianoRollView.setPreviewChord(preview)
                    // Also set the piano selector to the root note
                    selectedRoot = noteToRootName(event.note)
                    updateChordForRoot()
                    updateMeta()
                }
            }
            is MidiEvent.NoteOff -> { /* no action needed */ }
            is MidiEvent.ControlChange -> {
                when (event.cc) {
                    64 -> { // Sustain pedal → play / stop
                        if (event.value >= 64) togglePlay()
                    }
                    7 -> { // Volume knob → BPM 60–180
                        bpm = (60 + (event.value / 127f * 120f)).toInt()
                        bpmLabel.text = "$bpm bpm"
                    }
                }
            }
        }
    }

    /** Maps a MIDI note number to its root note name (C, C#, D … B) */
    private fun noteToRootName(midi: Int): String {
        val names = listOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")
        return names[midi % 12]
    }

    // ── Recording actions ────────────────────────────────────────────────────

    private fun startRecording() {
        if (!audioCapture.hasPermission()) {
            ActivityCompat.requestPermissions(this,
                arrayOf(android.Manifest.permission.RECORD_AUDIO), 101)
            return
        }
        if (editableChords.size >= 4) {
            chordHint.text = "All 4 chord slots are full — reset to record"
            return
        }

        // Update UI immediately on main thread
        isRecording = true
        micBtn.visibility       = View.GONE
        generateBtn.visibility  = View.GONE
        bpmLabel.visibility     = View.GONE
        waveformBtn.visibility  = View.VISIBLE
        waveformBtn.startPulsing()
        liveNoteText.visibility = View.VISIBLE
        liveNoteText.text       = "Starting…"
        confirmBtn.visibility   = View.VISIBLE
        cancelRecBtn.visibility = View.VISIBLE
        chordHint.text          = "Recording… sing or play a note"

        // Start AudioRecord on a background thread — avoids ANR
        lifecycleScope.launch(Dispatchers.IO) {
            val started = audioCapture.start()
            withContext(Dispatchers.Main) {
                if (!started) {
                    isRecording = false
                    restoreRecordingUI()
                    chordHint.text = "Could not start microphone"
                } else {
                    liveNoteText.text = "Listening…"
                }
            }
        }
    }

    private fun confirmRecording() {
        val notes = audioCapture.confirm()
        isRecording = false
        restoreRecordingUI()

        if (notes.isEmpty()) {
            chordHint.text = "No notes detected — try again"
            return
        }

        val chordName = ChordAnalyser.analyse(notes)
        val chord = EditableChord(chordName, notes.toMutableList())
        addEditableChord(chord)
        PianoSynth.playChord(notes)
        chordHint.text = "Recorded: $chordName"
    }

    private fun cancelRecording() {
        audioCapture.cancel()
        isRecording = false
        restoreRecordingUI()
        chordHint.text = "Recording cancelled"
    }

    private fun restoreRecordingUI() {
        waveformBtn.stopPulsing()
        waveformBtn.visibility  = View.GONE
        liveNoteText.visibility = View.GONE
        liveNoteText.text       = ""
        confirmBtn.visibility   = View.GONE
        cancelRecBtn.visibility = View.GONE
        micBtn.visibility       = View.VISIBLE
        generateBtn.visibility  = View.VISIBLE
        bpmLabel.visibility     = View.VISIBLE
    }

    /** Called on main thread each time YIN detects a note while recording */
    private fun onLiveNoteDetected(name: String, midi: Int) {
        liveNoteText.text = name
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                chordHint.text = "Microphone ready — tap 🎤 to record"
            } else {
                chordHint.text = "Microphone permission denied"
            }
        }
    }

    // ── 7-track display ──────────────────────────────────────────────────────

    private fun displaySevenTracks(chords: List<String>, genre: String, decade: String) {
        val tracks = ChordDeriver.deriveAllTracks(chords, genre, decade)
        fun List<String>.fmt() = joinToString("  ·  ")

        tvBass.text         = tracks.bass.fmt()
        tvRhythmGuitar.text = tracks.rhythmGuitar.fmt()
        tvPiano.text        = tracks.piano.joinToString("\n")
        tvPads.text         = tracks.pads.fmt()
        tvLead.text         = tracks.leadMelody.fmt()
        tvCounter.text      = tracks.counterMelody.fmt()
        tvPercussion.text   = tracks.percussion.joinToString("\n")

        // Restore full opacity now that data is loaded
        listOf(tvBass,tvRhythmGuitar,tvPiano,tvPads,tvLead,tvCounter,tvPercussion)
            .forEach { it.alpha = 1f }

        tracksSection.visibility = View.VISIBLE
    }

    private fun hideTracks() {
        tracksSection.visibility = View.GONE
        listOf(tvBass,tvRhythmGuitar,tvPiano,tvPads,tvLead,tvCounter,tvPercussion)
            .forEach { it.text = "—"; it.alpha = 0.6f }
    }

    // ── Engine ready ──────────────────────────────────────────────────────────

    private fun onEngineReady() {
        chordsByRoot = chordEngine.chordsByRoot()
        statusBadge.text = "Ready ✓"
        tokenHistory.clear(); tokenHistory.add(ChordEngine.BOS_TOKEN)
        selectedRoot = "A#"; updateChordForRoot()
        setBtnEnabled(predictBtn, true); updateMeta()
    }

    private fun updateChordForRoot() {
        selectedChordName = chordsByRoot[selectedRoot]?.firstOrNull() ?: selectedRoot
    }

    private fun updateMeta() {
        val g = if (selectedGenre  >= 0) chordEngine.genreLabels.getOrElse(selectedGenre)  { "—" } else "—"
        val d = if (selectedDecade >= 0) ChordEngine.DECADE_LABELS.getOrElse(selectedDecade) { "—" } else "—"
        val r = selectedChordName?.let { "$selectedRoot · " } ?: ""
        metaText.text = "$r$g · $d"
    }

    // ── Widgets ───────────────────────────────────────────────────────────────

    private fun setBtnEnabled(btn: TextView, on: Boolean) { btn.isEnabled = on; btn.alpha = if (on) 1f else 0.38f }
    private fun romanNumeral(i: Int) = listOf("I","II","III","IV")[i.coerceIn(0,3)]
    private fun lp(w: Int, h: Int, block: LinearLayout.LayoutParams.() -> Unit = {}) = LinearLayout.LayoutParams(w, h).apply(block)
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun secLabel(txt: String) = TextView(this).apply {
        text = txt; textSize = 10f; setTextColor(C.TXT_HINT); setPadding(dp(14), dp(10), dp(14), dp(6))
    }
    private fun hDivider() = View(this).apply {
        setBackgroundColor(C.BORDER)
        layoutParams = LinearLayout.LayoutParams(MATCH, 1).apply { setMargins(dp(14), 0, dp(14), 0) }
    }
    private fun pickerCard(label: String, value: String) = TextView(this).apply {
        text = "$label\n$value"; textSize = 12f; setTextColor(C.TXT_PRIMARY)
        setPadding(dp(10), dp(10), dp(10), dp(10)); setBackgroundColor(C.BG_SECTION)
    }
    private fun smallBadge(label: String, active: Boolean = false) = TextView(this).apply {
        text = label; textSize = 10f; setPadding(dp(7), dp(3), dp(7), dp(3))
        setTextColor(if (active) C.PURPLE_LITE else C.TXT_MUTED)
        setBackgroundColor(if (active) C.PURPLE_DARK else C.BG_CARD)
    }
    private fun iconBtn(label: String, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(C.TXT_MUTED); setBackgroundColor(C.BG_CARD)
        setPadding(dp(10), dp(8), dp(10), dp(8)); minWidth = dp(40); minimumHeight = dp(36)
        setOnClickListener { action() }
    }
    private fun pillBtn(label: String, primary: Boolean, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = 12f; gravity = Gravity.CENTER
        setTypeface(null, Typeface.BOLD); setTextColor(C.PURPLE_LITE)
        setBackgroundColor(if (primary) C.PURPLE else C.PURPLE_DARK)
        setPadding(dp(18), dp(9), dp(18), dp(9)); setOnClickListener { action() }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private fun showPicker(which: String) {
        val items: Array<String> = when (which) {
            "Genre"  -> (listOf("None") + chordEngine.genreLabels).toTypedArray()
            "Decade" -> (listOf("None") + ChordEngine.DECADE_LABELS).toTypedArray()
            else -> return
        }
        AlertDialog.Builder(this).setTitle(which).setItems(items) { _, pos ->
            val picked = items[pos]
            when (which) {
                "Genre"  -> { selectedGenre  = pos - 1; genreBtn.text  = "Genre\n$picked" }
                "Decade" -> { selectedDecade = pos - 1; decadeBtn.text = "Decade\n$picked" }
            }
            updateMeta()
        }.show()
    }

    private fun showBpmPicker() {
        val options = arrayOf("60","80","90","100","110","120","130","140","160","180","200")
        AlertDialog.Builder(this).setTitle("Tempo (BPM)").setItems(options) { _, pos ->
            bpm = options[pos].toInt(); bpmLabel.text = "$bpm bpm"
        }.show()
    }

    private fun showSnapPicker() {
        val options = arrayOf("1/4","1/8","1/16","1/32")
        AlertDialog.Builder(this).setTitle("Snap Resolution").setItems(options) { _, pos ->
            snapValue = options[pos]; snapBadge.text = snapValue
        }.show()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  PianoRollView — interactive: tap=add, long-press=delete, drag=move
// ─────────────────────────────────────────────────────────────────────────────
class PianoRollView(
    context: android.content.Context,
    private val onNoteAdded   : (chordIdx: Int, midi: Int) -> Unit,
    private val onNoteRemoved : (chordIdx: Int, midi: Int) -> Unit,
    private val onNoteMoved   : (fromChord: Int, toChord: Int, midi: Int) -> Unit
) : View(context) {

    private var chords     : List<EditableChord> = emptyList()
    private var playheadX  = -1f
    private var animJob    : kotlinx.coroutines.Job? = null

    // Touch tracking
    private var touchDownChord  = -1
    private var touchDownMidi   = -1
    private var longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private val LONG_PRESS_MS   = 500L      // ms before delete fires
    private var longPressFired  = false     // blocks ACTION_UP from re-adding the note

    private var touchDownX = 0f
    private var touchDownY = 0f

    // Display constants
    private val KEY_W      = 52f
    private val ROWS       = 14
    private val keyLabels  = listOf("C5","","B4","","A4","","G4","F4","","E4","","D4","","C4")
    private val keyIsBlack = listOf(false,true,false,true,false,true,false,false,true,false,true,false,true,false)

    // MIDI 48=C4 (row 13) .. 60=C5 (row 0)
    private val midiToRow  = mapOf(
        60 to 0,  59 to 2,  58 to 3,  57 to 4,  56 to 5,  55 to 6,
        53 to 7,  52 to 9,  51 to 10, 50 to 11, 49 to 12, 48 to 13
    )
    private val rowToMidi  = midiToRow.entries.associate { (k,v) -> v to k }

    private val bgPaint   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hlPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE; alpha = 60 }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val txtPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333352"); textSize = 20f; textAlign = Paint.Align.LEFT
    }
    private val phPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.ORANGE }
    private val deletePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3333"); style = Paint.Style.STROKE; strokeWidth = 3f
    }

    // Preview chord shown before user taps Predict (single ghost slot)
    private var previewChord: EditableChord? = null

    fun setPreviewChord(chord: EditableChord) {
        previewChord = chord
        if (chords.isEmpty()) invalidate()
    }

    fun setEditableChords(c: List<EditableChord>) {
        chords = c
        if (c.isNotEmpty()) previewChord = null  // preview replaced by real chords
        invalidate()
    }

    fun startPlayhead(chordIdx: Int, totalChords: Int, durationMs: Long) {
        animJob?.cancel()
        val gridW = width.toFloat() - KEY_W
        val slotW = gridW / maxOf(totalChords, 1)
        val startX = KEY_W + chordIdx * slotW
        val endX = startX + slotW
        val steps = 60L; val stepMs = durationMs / steps
        animJob = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            for (s in 0..steps) {
                playheadX = startX + (endX - startX) * s / steps.toFloat()
                invalidate(); kotlinx.coroutines.delay(stepMs)
            }
        }
    }

    fun stopPlayhead() { animJob?.cancel(); playheadX = -1f; invalidate() }

    // ── Touch handling ────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rowH  = height.toFloat() / ROWS
        val gridW = width.toFloat() - KEY_W
        val slotW = if (chords.isEmpty()) gridW else gridW / chords.size.coerceAtLeast(1)

        fun xToChord(x: Float) = ((x - KEY_W) / slotW).toInt().coerceIn(0, chords.size - 1)
        fun yToRow(y: Float)   = (y / rowH).toInt().coerceIn(0, ROWS - 1)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x; touchDownY = event.y
                longPressFired = false
                if (event.x < KEY_W || chords.isEmpty()) return true

                val ci   = xToChord(event.x)
                val row  = yToRow(event.y)
                val midi = rowToMidi[row]
                touchDownChord = ci; touchDownMidi = midi ?: -1

                // Start long-press timer — fires delete and sets longPressFired = true
                // so that ACTION_UP cannot re-add the note
                if (midi != null && chords.getOrNull(ci)?.midiNotes?.contains(midi) == true) {
                    longPressRunnable = Runnable {
                        longPressFired = true
                        onNoteRemoved(ci, midi)
                        touchDownChord = -1; touchDownMidi = -1
                        invalidate()
                    }
                    longPressHandler.postDelayed(longPressRunnable!!, LONG_PRESS_MS)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // Cancel long press if finger moves significantly
                val dx = Math.abs(event.x - touchDownX)
                val dy = Math.abs(event.y - touchDownY)
                if (dx > 10f || dy > 10f) {
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Always cancel the long press timer on finger lift
                longPressRunnable?.let { longPressHandler.removeCallbacks(it) }

                // If long press already fired (note deleted) — do nothing
                if (longPressFired) {
                    touchDownChord = -1; touchDownMidi = -1
                    return true
                }

                // Simple tap — add note if cell is empty, ignore if note exists
                if (event.x > KEY_W && chords.isNotEmpty()) {
                    val ci   = xToChord(event.x)
                    val row  = yToRow(event.y)
                    val midi = rowToMidi[row]
                    if (midi != null && chords.getOrNull(ci)?.midiNotes?.contains(midi) == false) {
                        onNoteAdded(ci, midi)
                    }
                }

                touchDownChord = -1; touchDownMidi = -1
                return true
            }
        }
        return false
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val gridW = w - KEY_W; val rowH = h / ROWS

        // Row backgrounds
        keyLabels.forEachIndexed { i, _ ->
            bgPaint.color = if (keyIsBlack[i]) Color.parseColor("#0D0D18") else Color.parseColor("#111120")
            canvas.drawRect(0f, i * rowH, w, (i+1) * rowH, bgPaint)
        }

        // Column backgrounds (subtle tint per chord slot)
        if (chords.isNotEmpty()) {
            val slotW = gridW / chords.size
            chords.forEachIndexed { ci, _ ->
                val cols = C.SLOTS[ci % C.SLOTS.size]
                bgPaint.color = cols[0]; bgPaint.alpha = 30
                canvas.drawRect(KEY_W + ci*slotW, 0f, KEY_W + (ci+1)*slotW, h, bgPaint)
                bgPaint.alpha = 255
            }
        }

        // Key-grid separator
        linePaint.color = Color.parseColor("#363650"); linePaint.strokeWidth = 1.5f
        canvas.drawLine(KEY_W, 0f, KEY_W, h, linePaint)

        // Beat / chord column lines
        if (chords.isNotEmpty()) {
            val slotW = gridW / chords.size
            for (i in 1 until chords.size) {
                val x = KEY_W + i * slotW
                linePaint.color = Color.parseColor("#363650"); linePaint.strokeWidth = 1.5f
                canvas.drawLine(x, 0f, x, h, linePaint)
            }
            // Sub-beat lines inside each slot
            for (ci in 0 until chords.size) {
                for (b in 1..3) {
                    val x = KEY_W + ci * slotW + slotW * b / 4f
                    linePaint.color = Color.parseColor("#1E1E2E"); linePaint.strokeWidth = 0.5f
                    canvas.drawLine(x, 0f, x, h, linePaint)
                }
            }
        }

        // Row dividers
        linePaint.color = Color.parseColor("#1A1A2A"); linePaint.strokeWidth = 0.5f
        keyLabels.indices.forEach { i -> canvas.drawLine(0f, i*rowH, KEY_W, i*rowH, linePaint) }

        // Key labels
        keyLabels.forEachIndexed { i, label ->
            if (label.isNotEmpty()) canvas.drawText(label, 4f, i*rowH + rowH*0.72f, txtPaint)
        }

        // Beat labels at top
        if (chords.isNotEmpty()) {
            val slotW = gridW / chords.size
            val beatTxt = Paint(txtPaint).apply { textSize = 18f; color = Color.parseColor("#2A2A3E") }
            chords.forEachIndexed { ci, chord ->
                canvas.drawText(chord.name, KEY_W + ci*slotW + 4f, 14f, beatTxt)
            }
        }

        // ── Draw notes ────────────────────────────────────────────────────────
        if (chords.isEmpty() && previewChord == null) {
            // Generic ghost placeholder — no key selected yet
            notePaint.color = Color.parseColor("#222238"); notePaint.alpha = 100
            val pw = gridW / 4f * 0.82f
            listOf(9 to 0, 5 to 0, 13 to 1, 6 to 1, 7 to 2, 9 to 2, 4 to 3, 11 to 3).forEach { (row, slot) ->
                val x = KEY_W + slot*(gridW/4f) + 4f; val y = row*rowH + 1f
                canvas.drawRoundRect(x, y, x+pw, y+rowH-2f, 4f, 4f, notePaint)
            }
            notePaint.alpha = 255
        } else if (chords.isEmpty() && previewChord != null) {
            // Show the selected key's chord as a preview in slot 0
            val preview = previewChord!!
            val color = C.NOTE_COLORS[0]; val nw = gridW * 0.88f
            val displayRows = preview.midiNotes.filter { it in 48..60 }.mapNotNull { midiToRow[it] }
            displayRows.forEachIndexed { ni, row ->
                notePaint.color = color; notePaint.alpha = if (ni == 0) 200 else 120
                val y = row * rowH + 1f
                canvas.drawRoundRect(KEY_W + 4f, y, KEY_W + nw, y + rowH - 2f, 4f, 4f, notePaint)
                canvas.drawRoundRect(KEY_W + 4f, y, KEY_W + nw, y + rowH * 0.3f, 4f, 4f, hlPaint)
            }
            // Label
            val lbl = Paint(txtPaint).apply { textSize = 18f; setColor(C.NOTE_COLORS[0]) }
            canvas.drawText(preview.name, KEY_W + 6f, 14f, lbl)
            notePaint.alpha = 255
        } else {
            val slotW = gridW / chords.size
            chords.forEachIndexed { ci, chord ->
                val color = C.NOTE_COLORS[ci % C.NOTE_COLORS.size]
                val nx = KEY_W + ci*slotW + 4f; val nw = slotW * 0.88f
                val displayRows = chord.midiNotes.filter { it in 48..60 }
                    .mapNotNull { midiToRow[it] }
                    .ifEmpty { emptyList() }

                displayRows.forEach { row ->
                    notePaint.color = color; notePaint.alpha = 220
                    val y = row*rowH + 1f
                    canvas.drawRoundRect(nx, y, nx+nw, y+rowH-2f, 4f, 4f, notePaint)

                    // Subtle highlight on top of note
                    canvas.drawRoundRect(nx, y, nx+nw, y+rowH*0.3f, 4f, 4f, hlPaint)
                }
            }
            notePaint.alpha = 255
        }

        // ── Playhead ──────────────────────────────────────────────────────────
        if (playheadX > 0f) {
            phPaint.style = Paint.Style.STROKE; phPaint.strokeWidth = 3f
            canvas.drawLine(playheadX, 0f, playheadX, h, phPaint)
            phPaint.style = Paint.Style.FILL
            canvas.drawPath(Path().apply {
                moveTo(playheadX-7f, 0f); lineTo(playheadX+7f, 0f); lineTo(playheadX, 13f); close()
            }, phPaint)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  VelocityView
// ─────────────────────────────────────────────────────────────────────────────
class VelocityView(context: android.content.Context) : View(context) {
    private val chordFracs = arrayOf(
        floatArrayOf(.80f,.65f,.90f), floatArrayOf(.70f,.55f,.85f),
        floatArrayOf(.95f,.72f,.60f), floatArrayOf(.75f,.88f,.50f)
    )
    private val allFracs   = chordFracs.flatMap { it.toList() }
    private val baseColors = listOf(
        C.PURPLE,C.PURPLE,C.PURPLE_DARK,C.PURPLE_DARK,
        C.PURPLE,C.PURPLE_DARK,C.PURPLE,C.PURPLE,
        C.PURPLE,C.PURPLE_DARK,C.PURPLE,C.PURPLE
    )
    private var activeChord = -1
    private var animScale   = 1f
    private var animJob     : kotlinx.coroutines.Job? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun animateForChord(chordIdx: Int, durationMs: Long) {
        animJob?.cancel(); activeChord = chordIdx
        animJob = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            var up = true; val endTime = System.currentTimeMillis() + durationMs
            while (System.currentTimeMillis() < endTime) {
                animScale = if (up) 1f else 0.7f; up = !up; invalidate()
                kotlinx.coroutines.delay(120L)
            }
        }
    }

    fun stopAnimation() { animJob?.cancel(); activeChord = -1; animScale = 1f; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val gap = 2f; val barW = (w - gap*(allFracs.size-1)) / allFracs.size
        allFracs.forEachIndexed { i, frac ->
            val grp     = i / 3; val isActive = grp == activeChord
            val scale   = if (isActive) animScale else if (activeChord >= 0) 0.5f else 1f
            paint.color = if (isActive) C.NOTE_COLORS[grp % C.NOTE_COLORS.size] else baseColors[i % baseColors.size]
            paint.alpha = if (isActive) 255 else if (activeChord >= 0) 100 else 200
            val x = i*(barW+gap); val barH = h*frac*scale
            canvas.drawRoundRect(x, h-barH, x+barW, h, 2f, 2f, paint)
        }
        paint.alpha = 255
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  PianoSelectorView
// ─────────────────────────────────────────────────────────────────────────────
class PianoSelectorView(
    context: android.content.Context,
    private val onRootSelected: (String) -> Unit
) : View(context) {
    private val whiteNotes = listOf("C","D","E","F","G","A","B")
    private val blackNotes = listOf("C#" to 0,"D#" to 1,null to -1,"F#" to 3,"G#" to 4,"A#" to 5)
    private var selectedRoot = "A#"

    private val whitePaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E8E8F0"); style = Paint.Style.FILL }
    private val blackPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A1A20"); style = Paint.Style.FILL }
    private val selWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#7F77DD"); style = Paint.Style.FILL }
    private val selBlackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#534AB7"); style = Paint.Style.FILL }
    private val borderPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3A3A50"); style = Paint.Style.STROKE; strokeWidth = 1f }
    private val labelPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#888899"); textSize = 22f; textAlign = Paint.Align.CENTER }
    private val selLblPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f; textAlign = Paint.Align.CENTER }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val kW = w/7f; val bW = kW*0.62f; val bH = h*0.60f
        whiteNotes.forEachIndexed { i, note ->
            val x = i*kW; val sel = note == selectedRoot
            canvas.drawRect(x+1f, 0f, x+kW-1f, h-1f, if (sel) selWhitePaint else whitePaint)
            canvas.drawRect(x+1f, 0f, x+kW-1f, h-1f, borderPaint)
            canvas.drawText(note, x+kW/2f, h-10f, if (sel) selLblPaint else labelPaint)
        }
        blackNotes.forEach { (note, idx) ->
            if (note == null) return@forEach
            val x = idx*kW + kW - bW/2f; val sel = note == selectedRoot
            canvas.drawRoundRect(x, 0f, x+bW, bH, 6f, 6f, if (sel) selBlackPaint else blackPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return false
        val kW = width.toFloat()/7f; val bW = kW*0.62f; val bH = height*0.60f
        val x = event.x; val y = event.y
        if (y < bH) {
            blackNotes.forEach { (note, idx) ->
                if (note == null) return@forEach
                val bx = idx*kW + kW - bW/2f
                if (x in bx..(bx+bW)) { selectedRoot = note; onRootSelected(note); invalidate(); return true }
            }
        }
        val idx = (x/kW).toInt().coerceIn(0, 6)
        selectedRoot = whiteNotes[idx]; onRootSelected(selectedRoot); invalidate()
        return true
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  WaveformButtonView — animated waveform icon shown while recording
//  Draws 5 vertical bars that pulse up and down like a sound wave.
// ─────────────────────────────────────────────────────────────────────────────
class WaveformButtonView(context: android.content.Context) : View(context) {

    private val barCount  = 5
    private val barHeights = FloatArray(barCount) { 0.4f }
    private val targets    = FloatArray(barCount) { 0.4f }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.FILL
    }
    private var animJob: kotlinx.coroutines.Job? = null

    fun startPulsing() {
        animJob?.cancel()
        animJob = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            while (true) {
                for (i in 0 until barCount) {
                    targets[i] = (0.2f + Math.random().toFloat() * 0.75f)
                }
                // Animate toward targets
                repeat(8) {
                    for (i in 0 until barCount) {
                        barHeights[i] += (targets[i] - barHeights[i]) * 0.4f
                    }
                    invalidate()
                    kotlinx.coroutines.delay(40L)
                }
            }
        }
    }

    fun stopPulsing() {
        animJob?.cancel()
        for (i in 0 until barCount) barHeights[i] = 0.4f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = minOf(w, h) / 2f

        // Black circle background
        canvas.drawCircle(w/2f, h/2f, r, bgPaint)

        // Waveform bars
        val barW  = w * 0.1f
        val gap   = (w - barCount * barW) / (barCount + 1)
        for (i in 0 until barCount) {
            val bh   = h * 0.2f + barHeights[i] * h * 0.55f
            val x    = gap + i * (barW + gap)
            val top  = (h - bh) / 2f
            canvas.drawRoundRect(x, top, x + barW, top + bh, barW/2f, barW/2f, paint)
        }
    }
}