package com.luciano.chordseq

import android.content.Context
import android.util.Log
import ai.onnxruntime.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.nio.FloatBuffer
import java.nio.LongBuffer

class ChordEngine(private val context: Context) {

    companion object {
        private const val TAG        = "ChordEngine"
        private const val MODEL_FILE = "conditional_small.onnx"
        private const val VOCAB_FILE = "token_to_chord.json"
        private const val NOTES_FILE = "chord_to_notes.json"

        const val BOS_TOKEN = 1L
        private const val PAD = 0L
        const val FIXED_SEQ  = 256

        // EOS tokens not in vocabulary — always masked
        private val SPECIAL_TOKENS = setOf(1033, 1034)

        const val N_GENRES  = 20
        const val N_DECADES = 8
        const val N_COND    = N_GENRES + N_DECADES

        val DECADE_LABELS = listOf(
            "1950s","1960s","1970s","1980s","1990s","2000s","2010s","2020s"
        )
    }

    private var ortEnv    : OrtEnvironment? = null
    private var ortSession: OrtSession?     = null

    private var tokenToChord: Map<String, List<String>> = emptyMap()
    private var chordToToken: Map<String, Int>          = emptyMap()

    var chordToNotes: Map<String, List<Int>> = emptyMap()
        private set

    var genreLabels: List<String> = emptyList()
        private set

    // ── Load ─────────────────────────────────────────────────────────────────

    fun load() {
        Log.d(TAG, "Loading model…")
        ortEnv = OrtEnvironment.getEnvironment()

        val bytes = context.assets.open(MODEL_FILE).readBytes()

        // Use optimised session options for faster inference
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)         // use all available CPU threads
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        ortSession = ortEnv!!.createSession(bytes, opts)
        Log.d(TAG, "Model loaded. Inputs: ${ortSession!!.inputNames}")

        // Pre-warm the model — first inference is always slow due to JIT compilation.
        // Running a dummy inference now means the user's first real prediction is fast.
        warmUp()

        // Vocabulary
        val vocabJson = context.assets.open(VOCAB_FILE).bufferedReader().use { it.readText() }
        tokenToChord  = Gson().fromJson(vocabJson, object : TypeToken<Map<String, List<String>>>() {}.type)
        chordToToken  = tokenToChord.entries.associate { (k, v) -> v[0] to k.toInt() }
        Log.d(TAG, "Vocab: ${tokenToChord.size} tokens")

        // Chord → MIDI notes
        val notesJson = context.assets.open(NOTES_FILE).bufferedReader().use { it.readText() }
        chordToNotes  = Gson().fromJson(notesJson, object : TypeToken<Map<String, List<Int>>>() {}.type)

        // Genre labels
        val condRaw   = context.assets.open("conditions.json").bufferedReader().use { it.readText() }
        val arrayPart = condRaw.trim()
            .let { s -> if (s.contains('[')) s.substring(s.indexOf('[')) else s }
            .let { s -> if (s.contains(']')) s.substring(0, s.indexOf(']') + 1) else s }
            .replace(Regex(",\\s*]"), "]")
        genreLabels = try {
            Gson().fromJson(arrayPart, object : TypeToken<List<String>>() {}.type)
        } catch (e: Exception) {
            listOf("Rock","Folk","Pop","Soundtrack","R&B, Funk & Soul","Country","Jazz",
                "Experimental","Religious Music","Reggae & Ska","Hip Hop","Electronic",
                "Comedy","Metal","Blues","World Music","Disco","Classical","New Age","Darkwave")
        }
        Log.d(TAG, "Genres: ${genreLabels.size}")
    }

    /** Run a dummy inference to trigger JIT compilation. ~200ms cost paid at startup. */
    private fun warmUp() {
        try {
            val env     = ortEnv ?: return
            val session = ortSession ?: return
            val t0      = System.currentTimeMillis()

            val dummyIds  = LongArray(FIXED_SEQ) { if (it == FIXED_SEQ - 1) BOS_TOKEN else 0L }
            val dummyCond = FloatArray(N_COND) { 0f }

            val tokTensor  = OnnxTensor.createTensor(env, LongBuffer.wrap(dummyIds),  longArrayOf(1L, FIXED_SEQ.toLong()))
            val condTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(dummyCond), longArrayOf(1L, N_COND.toLong()))
            val results    = session.run(mapOf("input.1" to tokTensor, "onnx::Gemm_1" to condTensor))

            results.close(); tokTensor.close(); condTensor.close()
            Log.d(TAG, "Warm-up done in ${System.currentTimeMillis() - t0}ms")
        } catch (e: Exception) {
            Log.w(TAG, "Warm-up failed (non-fatal): ${e.message}")
        }
    }

    // ── Chord helpers ─────────────────────────────────────────────────────────

    fun chordsByRoot(): Map<String, List<String>> {
        val roots = listOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")
        return roots.associateWith { root ->
            tokenToChord.values.map { it[0] }
                .filter { chord -> Regex("^([A-G][#]?)").find(chord)?.groupValues?.get(1) == root }
                .sorted()
        }
    }

    fun tokenForChord(name: String): Int? = chordToToken[name]
    fun notesForChord(name: String): List<Int> = chordToNotes[name] ?: emptyList()

    // ── Inference ─────────────────────────────────────────────────────────────

    data class Prediction(
        val chordName  : String,
        val midiNotes  : List<Int>,
        val tokenId    : Int,
        val inferenceMs: Long
    )

    fun predictNextChord(
        inputIds     : MutableList<Long>,
        genreWeights : FloatArray = FloatArray(N_GENRES),
        decadeWeights: FloatArray = FloatArray(N_DECADES),
        temperature  : Float     = 1.0f,
        useArgmax    : Boolean   = false
    ): Prediction {
        val session = ortSession ?: error("Call load() first")
        val env     = ortEnv    ?: error("OrtEnvironment null")
        val t0      = System.currentTimeMillis()

        // Left-pad token sequence to FIXED_SEQ
        val ids    = inputIds.takeLast(FIXED_SEQ)
        val padded = LongArray(FIXED_SEQ) { i ->
            val offset = FIXED_SEQ - ids.size
            if (i < offset) PAD else ids[i - offset]
        }

        // Build conditions vector [genres | decades]
        val cond = FloatArray(N_COND).also { arr ->
            genreWeights.copyInto(arr, 0)
            decadeWeights.copyInto(arr, N_GENRES)
        }

        val tokTensor  = OnnxTensor.createTensor(env, LongBuffer.wrap(padded),  longArrayOf(1L, FIXED_SEQ.toLong()))
        val condTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(cond),   longArrayOf(1L, N_COND.toLong()))
        val results    = session.run(mapOf("input.1" to tokTensor, "onnx::Gemm_1" to condTensor))
        val inferenceMs = System.currentTimeMillis() - t0

        @Suppress("UNCHECKED_CAST")
        val logits = (results[0].value as Array<Array<FloatArray>>)[0][FIXED_SEQ - 1].copyOf()

        // Mask EOS tokens
        for (t in SPECIAL_TOKENS) logits[t] = Float.NEGATIVE_INFINITY

        // Temperature scaling
        if (temperature != 1.0f) {
            for (i in logits.indices) {
                if (logits[i].isFinite()) logits[i] /= temperature
            }
        }

        val tokenId   = if (useArgmax) argmax(logits) else sample(logits)
        inputIds.add(tokenId.toLong())

        val chordName = tokenToChord[tokenId.toString()]?.firstOrNull() ?: "Token#$tokenId"
        val notes     = chordToNotes[chordName] ?: emptyList()

        Log.d(TAG, "token=$tokenId → $chordName  ${inferenceMs}ms")

        tokTensor.close()
        condTensor.close()
        results.close()

        return Prediction(chordName, notes, tokenId, inferenceMs)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun argmax(a: FloatArray) = a.indices.maxByOrNull { a[it] } ?: 0

    private fun sample(logits: FloatArray): Int {
        val max   = logits.filter { it.isFinite() }.maxOrNull() ?: 0f
        val exps  = FloatArray(logits.size) {
            if (logits[it].isFinite()) Math.exp((logits[it] - max).toDouble()).toFloat() else 0f
        }
        val sum   = exps.sum().coerceAtLeast(1e-8f)
        val probs = FloatArray(exps.size) { exps[it] / sum }
        var cum   = 0f
        val r     = Math.random().toFloat()
        for (i in probs.indices) {
            cum += probs[i]
            if (cum >= r) return i
        }
        return probs.size - 1
    }

    fun close() {
        ortSession?.close()
        ortEnv?.close()
    }
}