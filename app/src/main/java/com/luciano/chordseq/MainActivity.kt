package com.luciano.chordseq

import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var chordEngine : ChordEngine
    private val progression          = mutableListOf<ChordEngine.Prediction>()
    private val tokenHistory         = mutableListOf<Long>()
    private val inferenceTimes       = mutableListOf<Long>()

    private val temperatures = listOf(0.7f to "Safe 🎯", 1.0f to "Balanced 🎵", 1.3f to "Creative 🎲")
    private var tempIndex    = 1
    private var selectedGenre  = -1
    private var selectedDecade = -1

    private var selectedRoot      = "C"
    private var chordsByRoot      = mapOf<String, List<String>>()
    private var selectedChordName : String? = null

    private lateinit var statusText      : TextView
    private lateinit var perfText        : TextView
    private lateinit var chordCards      : List<ChordCardView>
    private lateinit var pianoSelector   : PianoSelectorView
    private lateinit var chordTypeSpinner: Spinner
    private lateinit var seedButton      : Button
    private lateinit var genreSpinner    : Spinner
    private lateinit var decadeSpinner   : Spinner
    private lateinit var tempButton      : Button
    private lateinit var predictButton   : Button
    private lateinit var playAllButton   : Button
    private lateinit var resetButton     : Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()

        chordEngine = ChordEngine(this)

        // Load the model AND pre-generate piano sounds concurrently.
        // This makes startup faster since both tasks run in parallel.
        lifecycleScope.launch {
            statusText.text = "ChordEngine: Loading…"

            val engineJob = async(Dispatchers.IO) { chordEngine.load() }
            val synthJob  = async(Dispatchers.IO) { PianoSynth.init() }

            try {
                engineJob.await()
                synthJob.await()
                onEngineReady()
            } catch (e: Exception) {
                Log.e("ChordAI", "Startup failed", e)
                statusText.text = "Error: ${e.message}"
            }
        }
    }

    // ── Build UI ──────────────────────────────────────────────────────────────

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 56, 32, 32)
        }

        statusText = TextView(this).apply { text = "Starting…"; textSize = 13f }
        perfText   = TextView(this).apply {
            text = "Inference: —"
            textSize = 11f
            setTextColor(Color.GRAY)
        }
        root.addView(statusText)
        root.addView(perfText)

        // Section 1: Seed chord
        root.addView(sectionLabel("① Starting Chord"))

        pianoSelector = PianoSelectorView(this) { r ->
            selectedRoot = r
            updateChordTypeSpinner()
        }
        root.addView(pianoSelector, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 120
        ).apply { topMargin = 8; bottomMargin = 8 })

        chordTypeSpinner = Spinner(this)
        root.addView(chordTypeSpinner)

        seedButton = Button(this).apply {
            text = "Set as First Chord"
            isEnabled = false
            setOnClickListener { onSeedChordSelected() }
        }
        root.addView(seedButton)

        // Section 2: Progression
        root.addView(sectionLabel("② Chord Progression"))

        val grid = GridLayout(this).apply { columnCount = 2; rowCount = 2 }
        val cards = (0 until 4).map { i ->
            ChordCardView(this, i + 1) { idx ->
                progression.getOrNull(idx)?.let { PianoSynth.playChord(it.midiNotes) }
            }
        }
        chordCards = cards
        cards.forEach { card ->
            grid.addView(card, GridLayout.LayoutParams().apply {
                width      = 0
                height     = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                setMargins(4, 4, 4, 4)
            })
        }
        root.addView(grid)

        // Section 3: Controls
        root.addView(sectionLabel("③ Style & Generate"))

        tempButton = Button(this).apply {
            text = temperatures[tempIndex].second
            isEnabled = false
            setOnClickListener {
                tempIndex = (tempIndex + 1) % temperatures.size
                text = temperatures[tempIndex].second
            }
        }
        root.addView(tempButton)

        root.addView(TextView(this).apply { text = "Genre"; textSize = 12f; setPadding(0,8,0,0) })
        genreSpinner = Spinner(this)
        root.addView(genreSpinner)

        root.addView(TextView(this).apply { text = "Decade"; textSize = 12f; setPadding(0,8,0,0) })
        decadeSpinner = Spinner(this).also { s ->
            s.adapter = ArrayAdapter(this,
                android.R.layout.simple_spinner_item,
                listOf("None") + ChordEngine.DECADE_LABELS
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            s.onItemSelectedListener = onSelect { pos -> selectedDecade = pos - 1 }
        }
        root.addView(decadeSpinner)

        predictButton = Button(this).apply {
            text = "▶  Predict Next Chord"
            isEnabled = false
            setOnClickListener { onPredictClicked() }
        }
        playAllButton = Button(this).apply {
            text = "🎹  Play All 4 Chords"
            isEnabled = false
            setOnClickListener { onPlayAllClicked() }
        }
        resetButton = Button(this).apply {
            text = "↺  Reset"
            isEnabled = false
            setOnClickListener { onResetClicked() }
        }
        root.addView(predictButton)
        root.addView(playAllButton)
        root.addView(resetButton)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize  = 14f
        setTypeface(null, Typeface.BOLD)
        setPadding(0, 24, 0, 8)
    }

    // ── Engine ready ──────────────────────────────────────────────────────────

    private fun onEngineReady() {
        statusText.text = "ChordEngine: READY ✓"
        chordsByRoot    = chordEngine.chordsByRoot()

        genreSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_item,
            listOf("None") + chordEngine.genreLabels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        genreSpinner.onItemSelectedListener = onSelect { pos -> selectedGenre = pos - 1 }

        updateChordTypeSpinner()

        tokenHistory.clear()
        tokenHistory.add(ChordEngine.BOS_TOKEN)

        seedButton.isEnabled    = true
        tempButton.isEnabled    = true
        predictButton.isEnabled = true
        resetButton.isEnabled   = true
    }

    private fun updateChordTypeSpinner() {
        val chords = chordsByRoot[selectedRoot] ?: emptyList()
        chordTypeSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_item, chords
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        chordTypeSpinner.onItemSelectedListener = onSelect { pos ->
            selectedChordName = chords.getOrNull(pos)
        }
        selectedChordName = chords.firstOrNull()
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun onSeedChordSelected() {
        val name    = selectedChordName ?: return
        val tokenId = chordEngine.tokenForChord(name) ?: run {
            statusText.text = "'$name' not in vocabulary"; return
        }
        val notes = chordEngine.notesForChord(name)

        tokenHistory.clear()
        tokenHistory.add(ChordEngine.BOS_TOKEN)
        tokenHistory.add(tokenId.toLong())

        progression.clear()
        chordCards.forEach { it.reset() }

        addToProgression(ChordEngine.Prediction(name, notes, tokenId, 0L))
        PianoSynth.playChord(notes)

        predictButton.isEnabled = true
        playAllButton.isEnabled = false
        statusText.text         = "Seed: $name — predict 3 more"
    }

    private fun onPredictClicked() {
        if (progression.size >= 4) return
        predictButton.isEnabled = false
        predictButton.text      = "Thinking…"

        val genreW = FloatArray(ChordEngine.N_GENRES).also { w ->
            if (selectedGenre in 0 until ChordEngine.N_GENRES) w[selectedGenre] = 1f
        }
        val decadeW = FloatArray(ChordEngine.N_DECADES).also { w ->
            if (selectedDecade in 0 until ChordEngine.N_DECADES) w[selectedDecade] = 1f
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val pred = try {
                chordEngine.predictNextChord(
                    inputIds      = tokenHistory,
                    genreWeights  = genreW,
                    decadeWeights = decadeW,
                    temperature   = temperatures[tempIndex].first
                )
            } catch (e: Exception) {
                Log.e("ChordAI", "Prediction failed", e)
                null
            }

            withContext(Dispatchers.Main) {
                if (pred != null) {
                    inferenceTimes.add(pred.inferenceMs)
                    val avg = inferenceTimes.average().toLong()
                    perfText.text = "Last: ${pred.inferenceMs}ms  Avg: ${avg}ms"
                    addToProgression(pred)
                    PianoSynth.playChord(pred.midiNotes)
                }
                predictButton.text      = "▶  Predict Next Chord"
                predictButton.isEnabled = progression.size < 4
                playAllButton.isEnabled = progression.size == 4
            }
        }
    }

    private fun onPlayAllClicked() {
        if (progression.isEmpty()) return
        playAllButton.isEnabled = false
        lifecycleScope.launch {
            for ((i, pred) in progression.withIndex()) {
                chordCards[i].setPlaying(true)
                PianoSynth.playChord(pred.midiNotes, durationMs = 1400)
                delay(1700)
                withContext(Dispatchers.Main) { chordCards[i].setPlaying(false) }
            }
            playAllButton.isEnabled = true
        }
    }

    private fun onResetClicked() {
        tokenHistory.clear()
        tokenHistory.add(ChordEngine.BOS_TOKEN)
        progression.clear()
        inferenceTimes.clear()
        chordCards.forEach { it.reset() }
        perfText.text           = "Inference: —"
        predictButton.text      = "▶  Predict Next Chord"
        predictButton.isEnabled = true
        playAllButton.isEnabled = false
        statusText.text         = "Reset — choose a starting chord"
    }

    private fun addToProgression(pred: ChordEngine.Prediction) {
        if (progression.size >= 4) return
        chordCards[progression.size].setChord(pred.chordName, pred.midiNotes.isNotEmpty())
        progression.add(pred)
    }

    private fun onSelect(block: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = block(pos)
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::chordEngine.isInitialized) chordEngine.close()
    }
}

// ── ChordCardView ─────────────────────────────────────────────────────────────

class ChordCardView(
    context: android.content.Context,
    private val slot: Int,
    private val onPlay: (Int) -> Unit
) : LinearLayout(context) {

    private val nameText : TextView
    private val hintText : TextView
    private var hasChord = false

    init {
        orientation = VERTICAL
        gravity     = android.view.Gravity.CENTER
        setPadding(16, 20, 16, 20)
        setBackgroundColor(Color.parseColor("#F5F5F5"))
        elevation = 2f

        addView(TextView(context).apply {
            text     = "Chord $slot"
            textSize = 10f
            setTextColor(Color.GRAY)
            gravity  = android.view.Gravity.CENTER
        })
        nameText = TextView(context).apply {
            text     = "—"
            textSize = 30f
            setTypeface(null, Typeface.BOLD)
            gravity  = android.view.Gravity.CENTER
        }
        hintText = TextView(context).apply {
            text     = ""
            textSize = 10f
            setTextColor(Color.LTGRAY)
            gravity  = android.view.Gravity.CENTER
        }
        addView(nameText)
        addView(hintText)

        setOnClickListener { if (hasChord) onPlay(slot - 1) }
    }

    fun setChord(name: String, playable: Boolean) {
        hasChord      = true
        nameText.text = name
        hintText.text = if (playable) "tap to play" else "—"
        setBackgroundColor(Color.parseColor("#EEF4FF"))
        nameText.setTextColor(Color.parseColor("#1A1A2E"))
    }

    fun setPlaying(playing: Boolean) {
        setBackgroundColor(if (playing) Color.parseColor("#4A90E2") else Color.parseColor("#EEF4FF"))
        nameText.setTextColor(if (playing) Color.WHITE else Color.parseColor("#1A1A2E"))
    }

    fun reset() {
        hasChord      = false
        nameText.text = "—"
        hintText.text = ""
        nameText.setTextColor(Color.BLACK)
        setBackgroundColor(Color.parseColor("#F5F5F5"))
    }
}

// ── PianoSelectorView ─────────────────────────────────────────────────────────

class PianoSelectorView(
    context: android.content.Context,
    private val onRootSelected: (String) -> Unit
) : View(context) {

    private val whiteNotes = listOf("C","D","E","F","G","A","B")
    private val blackNotes = listOf("C#" to 0, "D#" to 1, null to -1, "F#" to 3, "G#" to 4, "A#" to 5)
    private var selectedRoot = "C"

    private val whitePaint    = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val blackPaint    = Paint().apply { color = Color.parseColor("#222222"); style = Paint.Style.FILL }
    private val selPaint      = Paint().apply { color = Color.parseColor("#4A90E2"); style = Paint.Style.FILL }
    private val borderPaint   = Paint().apply { color = Color.parseColor("#888888"); style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val labelPaint    = Paint().apply { color = Color.parseColor("#555555"); textSize = 22f; textAlign = Paint.Align.CENTER }
    private val selLabelPaint = Paint().apply { color = Color.WHITE; textSize = 22f; textAlign = Paint.Align.CENTER }

    override fun onDraw(canvas: Canvas) {
        val w  = width.toFloat(); val h = height.toFloat()
        val kW = w / 7f; val bW = kW * 0.65f; val bH = h * 0.62f

        whiteNotes.forEachIndexed { i, note ->
            val x = i * kW
            canvas.drawRect(x + 1, 0f, x + kW - 1, h - 1, if (note == selectedRoot) selPaint else whitePaint)
            canvas.drawRect(x + 1, 0f, x + kW - 1, h - 1, borderPaint)
            canvas.drawText(note, x + kW / 2, h - 10f, if (note == selectedRoot) selLabelPaint else labelPaint)
        }
        blackNotes.forEach { (note, idx) ->
            if (note == null) return@forEach
            val x = idx * kW + kW - bW / 2
            canvas.drawRect(x, 0f, x + bW, bH, if (note == selectedRoot) selPaint else blackPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return false
        val w = width.toFloat(); val kW = w / 7f
        val bW = kW * 0.65f; val bH = height * 0.62f
        val x = event.x; val y = event.y

        if (y < bH) {
            blackNotes.forEach { (note, idx) ->
                if (note == null) return@forEach
                val bx = idx * kW + kW - bW / 2
                if (x >= bx && x <= bx + bW) {
                    selectedRoot = note; onRootSelected(note); invalidate(); return true
                }
            }
        }
        val idx = (x / kW).toInt().coerceIn(0, 6)
        selectedRoot = whiteNotes[idx]; onRootSelected(whiteNotes[idx]); invalidate()
        return true
    }
}