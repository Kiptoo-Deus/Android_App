package com.luciano.chordseq

import android.app.AlertDialog
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
//  MainActivity
// ─────────────────────────────────────────────────────────────────────────────
class MainActivity : AppCompatActivity() {

    private lateinit var chordEngine : ChordEngine
    private val progression          = mutableListOf<ChordEngine.Prediction>()
    private val tokenHistory         = mutableListOf<Long>()
    private val inferenceTimes       = mutableListOf<Long>()

    private val temperatures = listOf(0.7f to "Safe", 1.0f to "Balanced", 1.3f to "Creative")
    private var tempIndex      = 1
    private var selectedGenre  = -1
    private var selectedDecade = -1
    private var selectedRoot   = "A#"
    private var chordsByRoot   = mapOf<String, List<String>>()
    private var selectedChordName: String? = null

    // Playback state
    private var isPlaying      = false
    private var playJob        : Job? = null
    private var bpm            = 120
    private var snapValue      = "1/16"

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
    private lateinit var predictBtn    : TextView
    private lateinit var playBtn       : TextView
    private lateinit var generateBtn   : TextView
    private lateinit var bpmLabel      : TextView
    private lateinit var snapBadge     : TextView

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(C.BG_WRAP)
        buildUI()
        chordEngine = ChordEngine(this)
        lifecycleScope.launch {
            statusBadge.text = "Loading model…"
            val engineJob = async(Dispatchers.IO) { chordEngine.load() }
            val synthJob  = async(Dispatchers.IO) { PianoSynth.init() }
            try {
                engineJob.await(); synthJob.await()
                onEngineReady()
            } catch (e: Exception) {
                Log.e("ChordAnds", "Startup failed", e)
                statusBadge.text = "Error loading model"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        playJob?.cancel()
        if (::chordEngine.isInitialized) chordEngine.close()
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private fun buildUI() {
        val scroll = ScrollView(this).apply { setBackgroundColor(C.BG_WRAP) }
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG_SCREEN)
        }
        screen.addView(buildTopBar())
        screen.addView(hDivider())
        screen.addView(buildStyleSection())
        screen.addView(hDivider())
        screen.addView(buildPianoSection())
        screen.addView(hDivider())
        screen.addView(buildProgressionSection())
        screen.addView(hDivider())
        screen.addView(buildRollHeader())
        screen.addView(buildRollBody())
        screen.addView(hDivider())
        screen.addView(buildVelocitySection())
        screen.addView(hDivider())
        screen.addView(buildBottomBar())
        scroll.addView(screen)
        setContentView(scroll)
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
            gravity = Gravity.CENTER_VERTICAL
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = "ChordAnds"           // ← renamed
            textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(C.TXT_PRIMARY)
        })
        metaText = TextView(this).apply {
            text = "Select genre, decade & first chord"
            textSize = 10f; setTextColor(C.TXT_MUTED)
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
        bar.addView(dots)
        return bar
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
            selectedRoot = root; updateChordForRoot()
            chordHint.text = "$root selected · tap Predict to start"; updateMeta()
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
        rollTitleText = TextView(this).apply { text = "piano roll"; textSize = 10f; setTextColor(C.TXT_HINT) }
        row.addView(rollTitleText, lp(0, WRAP) { weight = 1f })

        // Snap badge — tappable
        snapBadge = smallBadge(snapValue, active = true)
        snapBadge.setOnClickListener { showSnapPicker() }
        row.addView(snapBadge)
        row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) })
        row.addView(smallBadge("snap"))
        return row
    }

    private fun buildRollBody(): View {
        pianoRollView = PianoRollView(this)
        return pianoRollView.also {
            it.layoutParams = LinearLayout.LayoutParams(MATCH, dp(190)).apply {
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

    private fun buildBottomBar(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Row 1: play | bpm | generate
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(10), dp(14), dp(6)); gravity = Gravity.CENTER_VERTICAL
        }
        playBtn  = iconBtn("▶") { togglePlay() }
        bpmLabel = TextView(this).apply {
            text = "$bpm bpm"; textSize = 11f; setTextColor(C.TXT_MUTED)
            // Tap to change BPM
            setOnClickListener { showBpmPicker() }
        }
        generateBtn = pillBtn("Generate ↗", primary = true) { onGenerateClicked() }
        generateBtn.isEnabled = false; generateBtn.alpha = 0.38f
        row1.addView(playBtn)
        row1.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(10), 1) })
        row1.addView(bpmLabel, lp(0, WRAP) { weight = 1f })
        row1.addView(generateBtn)
        col.addView(row1)

        // Row 2: predict | reset
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), 0, dp(14), dp(14)); gravity = Gravity.CENTER_VERTICAL
        }
        predictBtn = pillBtn("Predict next ↗", primary = false) { onPredictClicked() }
        predictBtn.isEnabled = false; predictBtn.alpha = 0.38f
        val resetBtn = iconBtn("↺") { onResetClicked() }
        row2.addView(predictBtn, lp(0, WRAP) { weight = 1f; marginEnd = dp(8) })
        row2.addView(resetBtn)
        col.addView(row2)
        return col
    }

    // ── Timeline ──────────────────────────────────────────────────────────────

    private fun rebuildTimeline() {
        timeline.removeAllViews()
        for (i in 0..3) {
            val view = when {
                progression.getOrNull(i) != null -> filledSlot(i, progression[i])
                i == progression.size             -> predictSlot()
                else                              -> emptySlot(i)
            }
            timeline.addView(view, lp(0, MATCH) { weight = 1f })
        }
    }

    private fun filledSlot(i: Int, pred: ChordEngine.Prediction): View {
        val cols = C.SLOTS[i % C.SLOTS.size]
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(7), dp(8), 0); setBackgroundColor(cols[0])
            addView(TextView(this@MainActivity).apply {
                text = pred.chordName; textSize = 13f
                setTypeface(null, Typeface.BOLD); setTextColor(cols[1])
            })
            addView(TextView(this@MainActivity).apply {
                text = "${romanNumeral(i)} · maj"; textSize = 9f; setTextColor(cols[2])
            })
            addView(View(this@MainActivity).apply {
                setBackgroundColor(cols[3])
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(3)).apply { topMargin = dp(5) }
            })
            setOnClickListener { PianoSynth.playChord(pred.midiNotes) }
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

    // ── Widgets ───────────────────────────────────────────────────────────────

    private fun secLabel(txt: String) = TextView(this).apply {
        text = txt; textSize = 10f; setTextColor(C.TXT_HINT)
        setPadding(dp(14), dp(10), dp(14), dp(6))
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
        text = label; textSize = 10f
        setPadding(dp(7), dp(3), dp(7), dp(3))
        setTextColor(if (active) C.PURPLE_LITE else C.TXT_MUTED)
        setBackgroundColor(if (active) C.PURPLE_DARK else C.BG_CARD)
    }

    private fun iconBtn(label: String, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(C.TXT_MUTED); setBackgroundColor(C.BG_CARD)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        minWidth = dp(40); minimumHeight = dp(36)
        setOnClickListener { action() }
    }

    private fun pillBtn(label: String, primary: Boolean, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = 12f; gravity = Gravity.CENTER
        setTypeface(null, Typeface.BOLD); setTextColor(C.PURPLE_LITE)
        setBackgroundColor(if (primary) C.PURPLE else C.PURPLE_DARK)
        setPadding(dp(18), dp(9), dp(18), dp(9))
        setOnClickListener { action() }
    }

    private fun romanNumeral(i: Int) = listOf("I","II","III","IV")[i.coerceIn(0, 3)]

    private fun lp(w: Int, h: Int, block: LinearLayout.LayoutParams.() -> Unit = {}) =
        LinearLayout.LayoutParams(w, h).apply(block)

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private fun showPicker(which: String) {
        val items: Array<String> = when (which) {
            "Genre"  -> (listOf("None") + chordEngine.genreLabels).toTypedArray()
            "Decade" -> (listOf("None") + ChordEngine.DECADE_LABELS).toTypedArray()
            else     -> return
        }
        AlertDialog.Builder(this).setTitle(which)
            .setItems(items) { _, pos ->
                val picked = items[pos]
                when (which) {
                    "Genre"  -> { selectedGenre  = pos - 1; genreBtn.text  = "Genre\n$picked" }
                    "Decade" -> { selectedDecade = pos - 1; decadeBtn.text = "Decade\n$picked" }
                }
                updateMeta()
            }.show()
    }

    private fun showBpmPicker() {
        val options = arrayOf("60 bpm","80 bpm","90 bpm","100 bpm","110 bpm",
            "120 bpm","130 bpm","140 bpm","160 bpm","180 bpm","200 bpm")
        AlertDialog.Builder(this).setTitle("Tempo")
            .setItems(options) { _, pos ->
                bpm = options[pos].replace(" bpm","").toInt()
                bpmLabel.text = "$bpm bpm"
            }.show()
    }

    private fun showSnapPicker() {
        val options = arrayOf("1/4","1/8","1/16","1/32")
        AlertDialog.Builder(this).setTitle("Snap Resolution")
            .setItems(options) { _, pos ->
                snapValue = options[pos]
                snapBadge.text = snapValue
            }.show()
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

    private fun setBtnEnabled(btn: TextView, on: Boolean) {
        btn.isEnabled = on; btn.alpha = if (on) 1f else 0.38f
    }

    // ── Playback with animated playhead ──────────────────────────────────────

    /**
     * Plays all chords sequentially. For each chord:
     *  1. Highlight the chord slot in the timeline
     *  2. Animate the playhead across that chord's column in the piano roll
     *  3. Animate the velocity bars for that chord
     *  4. Play the audio
     */
    private fun playProgression() {
        playJob?.cancel()
        val chordDurationMs = (60_000L / bpm) * 4   // one bar per chord at current BPM

        playJob = lifecycleScope.launch {
            setBtnEnabled(generateBtn, false)
            playBtn.text = "⏸"

            val totalChords = progression.size
            for ((i, pred) in progression.withIndex()) {

                if (!isActive) break

                // Highlight active chord slot
                highlightTimelineSlot(i)

                // Tell views which chord is active and start of sweep
                pianoRollView.startPlayhead(i, totalChords, chordDurationMs)
                velocityView.animateForChord(i, chordDurationMs)

                // Play audio
                PianoSynth.playChord(pred.midiNotes, durationMs = chordDurationMs.toInt())

                delay(chordDurationMs)
            }

            // Playback finished
            withContext(Dispatchers.Main) {
                isPlaying = false
                playBtn.text = "▶"
                setBtnEnabled(generateBtn, progression.size >= 2)
                pianoRollView.stopPlayhead()
                velocityView.stopAnimation()
                rebuildTimeline()   // restore normal slot colours
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
        if (progression.size >= 4) return

        if (progression.isEmpty()) {
            val name = selectedChordName ?: run { chordHint.text = "Pick a starting note first"; return }
            val tokenId = chordEngine.tokenForChord(name)
                ?: chordsByRoot[selectedRoot]?.firstOrNull()?.let { chordEngine.tokenForChord(it) }
                ?: run { chordHint.text = "'$name' not in model vocabulary"; return }
            val notes = chordEngine.notesForChord(name)
            tokenHistory.clear(); tokenHistory.add(ChordEngine.BOS_TOKEN)
            tokenHistory.add(tokenId.toLong())
            addToProgression(ChordEngine.Prediction(name, notes, tokenId, 0L))
            PianoSynth.playChord(notes)
            if (progression.size >= 4) return
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
            }.onFailure { Log.e("ChordAnds", "Prediction failed", it) }.getOrNull()

            withContext(Dispatchers.Main) {
                if (pred != null) {
                    inferenceTimes.add(pred.inferenceMs)
                    addToProgression(pred)
                    PianoSynth.playChord(pred.midiNotes)
                    pianoRollView.setChords(progression)
                    rollTitleText.text = "piano roll · ${progression.size} chord${if (progression.size > 1) "s" else ""}"
                }
                predictBtn.text = "Predict next ↗"
                setBtnEnabled(predictBtn, progression.size < 4)
                setBtnEnabled(generateBtn, progression.size >= 2)
            }
        }
    }

    private fun onGenerateClicked() {
        if (progression.isEmpty()) return
        isPlaying = true
        playProgression()
    }

    private fun togglePlay() {
        if (progression.isEmpty()) return
        isPlaying = !isPlaying
        if (isPlaying) {
            playProgression()
        } else {
            playJob?.cancel()
            playBtn.text = "▶"
            setBtnEnabled(generateBtn, progression.size >= 2)
            pianoRollView.stopPlayhead()
            velocityView.stopAnimation()
            rebuildTimeline()
        }
    }

    private fun onResetClicked() {
        playJob?.cancel()
        isPlaying = false; playBtn.text = "▶"
        tokenHistory.clear(); tokenHistory.add(ChordEngine.BOS_TOKEN)
        progression.clear(); inferenceTimes.clear()
        rebuildTimeline()
        pianoRollView.setChords(emptyList()); pianoRollView.stopPlayhead()
        velocityView.stopAnimation()
        rollTitleText.text = "piano roll"
        chordHint.text     = "tap a key to set the first chord"
        setBtnEnabled(predictBtn, true); setBtnEnabled(generateBtn, false)
        updateMeta()
    }

    private fun addToProgression(pred: ChordEngine.Prediction) {
        if (progression.size >= 4) return
        progression.add(pred); rebuildTimeline()
        val rem = 4 - progression.size
        chordHint.text = "${pred.chordName} added · $rem slot${if (rem != 1) "s" else ""} remaining"
        setBtnEnabled(predictBtn, progression.size < 4)
        setBtnEnabled(generateBtn, progression.size >= 2)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  PianoRollView — animated playhead
// ─────────────────────────────────────────────────────────────────────────────
class PianoRollView(context: android.content.Context) : View(context) {

    private var chords     : List<ChordEngine.Prediction> = emptyList()
    private var playheadX  : Float = -1f   // -1 = hidden
    private var animJob    : kotlinx.coroutines.Job? = null

    private val keyLabels  = listOf("C5","","B4","","A4","","G4","F4","","E4","","D4","","C4")
    private val keyIsBlack = listOf(false,true,false,true,false,true,false,false,true,false,true,false,true,false)
    private val midiToRow  = mapOf(
        60 to 0, 59 to 2, 58 to 3, 57 to 4, 56 to 5, 55 to 6,
        53 to 7, 52 to 9, 51 to 10, 50 to 11, 49 to 12, 48 to 13
    )

    private val bgPaint   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val txtPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333352"); textSize = 20f; textAlign = Paint.Align.LEFT
    }
    private val phPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.ORANGE }

    fun setChords(c: List<ChordEngine.Prediction>) { chords = c; invalidate() }

    /**
     * Animate the playhead sweeping across chord slot [chordIdx] over [durationMs].
     * Called once per chord during playback.
     */
    fun startPlayhead(chordIdx: Int, totalChords: Int, durationMs: Long) {
        animJob?.cancel()
        val keyW   = 52f
        val gridW  = width.toFloat() - keyW
        val slotW  = gridW / maxOf(totalChords, 1)
        val startX = keyW + chordIdx * slotW
        val endX   = startX + slotW
        val steps  = 60
        val stepMs = durationMs / steps

        animJob = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            for (s in 0..steps) {
                playheadX = startX + (endX - startX) * s / steps.toFloat()
                invalidate()
                kotlinx.coroutines.delay(stepMs)
            }
        }
    }

    fun stopPlayhead() {
        animJob?.cancel(); playheadX = -1f; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val keyW = 52f; val gridW = w - keyW
        val rows = keyLabels.size; val rowH = h / rows

        // Row backgrounds
        keyLabels.forEachIndexed { i, _ ->
            bgPaint.color = if (keyIsBlack[i]) Color.parseColor("#0D0D18") else Color.parseColor("#111120")
            canvas.drawRect(0f, i * rowH, w, (i + 1) * rowH, bgPaint)
        }

        // Key-grid separator
        linePaint.color = Color.parseColor("#363650"); linePaint.strokeWidth = 1.5f
        canvas.drawLine(keyW, 0f, keyW, h, linePaint)

        // Beat lines
        for (beat in 1..3) {
            val x = keyW + gridW * beat / 4f
            linePaint.strokeWidth = if (beat == 2) 1.5f else 0.5f
            linePaint.color = if (beat == 2) Color.parseColor("#363650") else Color.parseColor("#2A2A3E")
            canvas.drawLine(x, 0f, x, h, linePaint)
        }

        // Row dividers (key side)
        linePaint.color = Color.parseColor("#1A1A2A"); linePaint.strokeWidth = 0.5f
        keyLabels.indices.forEach { i -> canvas.drawLine(0f, i * rowH, keyW, i * rowH, linePaint) }

        // Key labels
        keyLabels.forEachIndexed { i, label ->
            if (label.isNotEmpty()) canvas.drawText(label, 4f, i * rowH + rowH * 0.72f, txtPaint)
        }

        // Beat numbers
        val beatTxt = Paint(txtPaint).apply { textSize = 18f; color = Color.parseColor("#2A2A3E") }
        for (b in 0..3) canvas.drawText("${b+1}", keyW + gridW * b / 4f + 4f, 14f, beatTxt)

        // Notes
        if (chords.isEmpty()) {
            notePaint.color = Color.parseColor("#222238"); notePaint.alpha = 160
            val pw = gridW / 4f * 0.82f
            listOf(9 to 0, 5 to 0, 13 to 1, 6 to 1, 7 to 2, 9 to 2, 4 to 3, 11 to 3).forEach { (row, slot) ->
                val x = keyW + slot * (gridW / 4f) + 4f; val y = row * rowH + 1f
                canvas.drawRoundRect(x, y, x + pw, y + rowH - 2f, 4f, 4f, notePaint)
            }
            notePaint.alpha = 255
        } else {
            val slotW = gridW / 4f
            chords.forEachIndexed { ci, pred ->
                val color = C.NOTE_COLORS[ci % C.NOTE_COLORS.size]
                val nx = keyW + ci * slotW + 4f; val nw = slotW * 0.84f
                val displayRows = pred.midiNotes.filter { it in 48..60 }
                    .mapNotNull { midiToRow[it] }.take(4)
                    .ifEmpty { listOf(listOf(9, 5, 7, 4)[ci % 4]) }
                displayRows.forEachIndexed { ni, row ->
                    notePaint.color = color; notePaint.alpha = if (ni == 0) 230 else 140
                    val y = row * rowH + 1f
                    canvas.drawRoundRect(nx, y, nx + nw, y + rowH - 2f, 4f, 4f, notePaint)
                }
            }
            notePaint.alpha = 255
        }

        // Animated playhead
        if (playheadX > 0f) {
            phPaint.style = Paint.Style.STROKE; phPaint.strokeWidth = 3f
            canvas.drawLine(playheadX, 0f, playheadX, h, phPaint)
            phPaint.style = Paint.Style.FILL
            canvas.drawPath(Path().apply {
                moveTo(playheadX - 7f, 0f); lineTo(playheadX + 7f, 0f)
                lineTo(playheadX, 13f); close()
            }, phPaint)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  VelocityView — animated bars per chord
// ─────────────────────────────────────────────────────────────────────────────
class VelocityView(context: android.content.Context) : View(context) {

    // Static height fractions per chord (4 groups of 3 bars)
    private val chordFracs = arrayOf(
        floatArrayOf(.80f, .65f, .90f),
        floatArrayOf(.70f, .55f, .85f),
        floatArrayOf(.95f, .72f, .60f),
        floatArrayOf(.75f, .88f, .50f)
    )
    private val allFracs   = chordFracs.flatMap { it.toList() }
    private val baseColors = listOf(
        C.PURPLE, C.PURPLE, C.PURPLE_DARK, C.PURPLE_DARK,
        C.PURPLE, C.PURPLE_DARK, C.PURPLE, C.PURPLE,
        C.PURPLE, C.PURPLE_DARK, C.PURPLE, C.PURPLE
    )

    private var activeChord  = -1    // -1 = no active chord
    private var animScale    = 1f    // 0..1 pulse during playback
    private var animJob      : kotlinx.coroutines.Job? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun animateForChord(chordIdx: Int, durationMs: Long) {
        animJob?.cancel()
        activeChord = chordIdx
        animJob = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            val pulseMs = 120L
            var up = true
            val endTime = System.currentTimeMillis() + durationMs
            while (System.currentTimeMillis() < endTime) {
                animScale = if (up) 1f else 0.7f
                up = !up
                invalidate()
                kotlinx.coroutines.delay(pulseMs)
            }
        }
    }

    fun stopAnimation() {
        animJob?.cancel(); activeChord = -1; animScale = 1f; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val count = allFracs.size
        val gap = 2f; val barW = (w - gap * (count - 1)) / count

        allFracs.forEachIndexed { i, frac ->
            val chordGroup = i / 3
            val isActive   = chordGroup == activeChord
            val scale      = if (isActive) animScale else if (activeChord >= 0) 0.5f else 1f
            val color      = if (isActive) C.NOTE_COLORS[chordGroup % C.NOTE_COLORS.size]
            else baseColors[i % baseColors.size]
            paint.color = color
            paint.alpha = if (isActive) 255 else if (activeChord >= 0) 100 else 200

            val x    = i * (barW + gap)
            val barH = h * frac * scale
            canvas.drawRoundRect(x, h - barH, x + barW, h, 2f, 2f, paint)
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
    private val blackNotes = listOf("C#" to 0, "D#" to 1, null to -1, "F#" to 3, "G#" to 4, "A#" to 5)
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
        val kW = w / 7f; val bW = kW * 0.62f; val bH = h * 0.60f
        whiteNotes.forEachIndexed { i, note ->
            val x = i * kW; val sel = note == selectedRoot
            canvas.drawRect(x+1f, 0f, x+kW-1f, h-1f, if (sel) selWhitePaint else whitePaint)
            canvas.drawRect(x+1f, 0f, x+kW-1f, h-1f, borderPaint)
            canvas.drawText(note, x+kW/2f, h-10f, if (sel) selLblPaint else labelPaint)
        }
        blackNotes.forEach { (note, idx) ->
            if (note == null) return@forEach
            val x = idx * kW + kW - bW/2f; val sel = note == selectedRoot
            canvas.drawRoundRect(x, 0f, x+bW, bH, 6f, 6f, if (sel) selBlackPaint else blackPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return false
        val kW = width.toFloat() / 7f; val bW = kW * 0.62f; val bH = height * 0.60f
        val x = event.x; val y = event.y
        if (y < bH) {
            blackNotes.forEach { (note, idx) ->
                if (note == null) return@forEach
                val bx = idx * kW + kW - bW/2f
                if (x in bx..(bx+bW)) { selectedRoot = note; onRootSelected(note); invalidate(); return true }
            }
        }
        val idx = (x / kW).toInt().coerceIn(0, 6)
        selectedRoot = whiteNotes[idx]; onRootSelected(selectedRoot); invalidate()
        return true
    }
}