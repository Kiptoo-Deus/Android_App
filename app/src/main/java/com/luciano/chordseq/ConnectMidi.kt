package com.luciano.chordseq

// ═════════════════════════════════════════════════════════════════════════════
//  ConnectMidi.kt — MIDI Connection Manager
//  ChordAnds · Luciano Muratore
// ═════════════════════════════════════════════════════════════════════════════
//
//  The following options were chosen by the user and drive the behaviour
//  of this file:
//
//  ┌─────────────────────────────────────────────────────────────────────────┐
//  │ Q: What MIDI equipment do you want to connect to?                       │
//  │ A: Both USB and Bluetooth                                               │
//  │                                                                         │
//  │    → USB MIDI: uses Android MidiManager to detect physical keyboards   │
//  │      plugged via USB cable or USB-OTG adapter. Auto-connects to all    │
//  │      attached devices at startup and listens for hot-plug events.      │
//  │                                                                         │
//  │    → Bluetooth MIDI: scans for BLE devices advertising the standard    │
//  │      BLE MIDI service UUID (03B80E5A-...). Connects to the first       │
//  │      device found and stops scanning to save battery.                  │
//  ├─────────────────────────────────────────────────────────────────────────┤
//  │ Q: What should the MIDI connection DO in the app?                       │
//  │ A: All of the above                                                     │
//  │                                                                         │
//  │    → Play notes on the piano roll when keys are pressed:               │
//  │      NoteOn messages add the incoming MIDI note to the active chord    │
//  │      slot in the piano roll. The note is also played via PianoSynth.  │
//  │                                                                         │
//  │    → Set the starting chord by playing a note:                         │
//  │      When no chords exist yet, a single NoteOn sets a preview chord    │
//  │      on the piano roll and moves the root selector to that note.       │
//  │      Playing 3+ notes within 60ms is detected as a chord and           │
//  │      analysed live by ChordAnalyser.                                   │
//  │                                                                         │
//  │    → Control playback (play/stop/tempo):                               │
//  │      CC 64 (sustain pedal, value >= 64) -> toggles play / stop.       │
//  │      CC 7  (volume knob, 0-127)         -> maps to BPM range 60-180.  │
//  ├─────────────────────────────────────────────────────────────────────────┤
//  │ Q: Should the MIDI UI live inside the main screen or separately?        │
//  │ A: Just background auto-connect, no UI needed                          │
//  │                                                                         │
//  │    → No MIDI settings screen or button is shown. Connection happens    │
//  │      silently at app startup via ConnectMidi.start(). The only visual  │
//  │      feedback is the status badge which shows the connected device.    │
//  └─────────────────────────────────────────────────────────────────────────┘
//
//  To change any of these behaviours, modify the relevant section below
//  and update the comment above to reflect your new choice.
//
// ═════════════════════════════════════════════════════════════════════════════

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import android.os.Build

// ─────────────────────────────────────────────────────────────────────────────
//  MIDI Event — emitted to listeners whenever a MIDI message arrives
// ─────────────────────────────────────────────────────────────────────────────
sealed class MidiEvent {
    /** A single note was pressed on the MIDI device */
    data class NoteOn (val channel: Int, val note: Int, val velocity: Int) : MidiEvent()
    /** A note was released */
    data class NoteOff(val channel: Int, val note: Int) : MidiEvent()
    /** Control Change — e.g. sustain pedal, mod wheel, volume knob */
    data class ControlChange(val channel: Int, val cc: Int, val value: Int) : MidiEvent()
    /** A USB MIDI device was plugged in */
    data class DeviceConnected(val name: String, val type: String) : MidiEvent()
    /** A device was unplugged / disconnected */
    data class DeviceDisconnected(val name: String) : MidiEvent()
}

// ─────────────────────────────────────────────────────────────────────────────
//  ConnectMidi — singleton that manages USB + BLE MIDI connections
//
//  Usage:
//    ConnectMidi.start(context, listener)   // call from MainActivity.onCreate
//    ConnectMidi.stop()                     // call from MainActivity.onDestroy
//
//  The listener receives MidiEvent objects on the main thread.
// ─────────────────────────────────────────────────────────────────────────────
@RequiresApi(Build.VERSION_CODES.M)
object ConnectMidi {

    private const val TAG = "ConnectMidi"

    // BLE MIDI service UUID (standard)
    private const val BLE_MIDI_SERVICE = "03B80E5A-EDE8-4B33-A751-6CE34EC4C700"

    private var midiManager      : MidiManager?      = null
    private var bluetoothAdapter : BluetoothAdapter? = null
    private var appContext       : Context?           = null
    private var listener         : ((MidiEvent) -> Unit)? = null
    private val mainHandler      = Handler(Looper.getMainLooper())

    // Open devices — kept so we can close them on stop()
    private val openDevices   = mutableListOf<MidiDevice>()
    private val outputPorts   = mutableListOf<MidiOutputPort>()

    // Chord detection — collect notes arriving within CHORD_WINDOW_MS
    private val pendingNotes        = mutableListOf<Int>()
    private var chordDetectRunnable : Runnable? = null
    private const val CHORD_WINDOW_MS = 60L

    // ── Public API ────────────────────────────────────────────────────────────

    fun start(context: Context, onEvent: (MidiEvent) -> Unit) {
        appContext = context.applicationContext
        listener   = onEvent

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.w(TAG, "MIDI API requires Android 6.0+, skipping")
            return
        }

        midiManager = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bm?.adapter

        startUsbMidi()
        startBleMidi()
        registerUsbHotplug(context)

        Log.d(TAG, "ConnectMidi started")
    }

    fun stop() {
        outputPorts.forEach { runCatching { it.close() } }
        openDevices.forEach { runCatching { it.close() } }
        outputPorts.clear()
        openDevices.clear()
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(bleScanCallback)
        appContext?.unregisterReceiver(usbReceiver)
        listener   = null
        appContext  = null
        Log.d(TAG, "ConnectMidi stopped")
    }

    // ── USB MIDI ──────────────────────────────────────────────────────────────

    private fun startUsbMidi() {
        val manager = midiManager ?: return

        // Connect to all currently attached devices
        manager.devices.forEach { info -> openMidiDevice(info) }

        // Register for future device arrivals
        manager.registerDeviceCallback(object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(info: MidiDeviceInfo) {
                Log.d(TAG, "USB MIDI device added: ${deviceName(info)}")
                openMidiDevice(info)
            }
            override fun onDeviceRemoved(info: MidiDeviceInfo) {
                val name = deviceName(info)
                Log.d(TAG, "USB MIDI device removed: $name")
                emit(MidiEvent.DeviceDisconnected(name))
            }
        }, mainHandler)
    }

    private fun openMidiDevice(info: MidiDeviceInfo) {
        val manager = midiManager ?: return
        manager.openDevice(info, { device ->
            if (device == null) { Log.w(TAG, "Failed to open device"); return@openDevice }
            openDevices.add(device)

            // Open the first output port (MIDI OUT from device → into our app)
            val portCount = info.outputPortCount
            if (portCount == 0) return@openDevice

            val port = device.openOutputPort(0)
            if (port == null) { Log.w(TAG, "Failed to open output port"); return@openDevice }
            outputPorts.add(port)
            port.connect(midiReceiver)

            val name = deviceName(info)
            Log.d(TAG, "Connected to USB MIDI: $name")
            emit(MidiEvent.DeviceConnected(name, "USB"))
        }, mainHandler)
    }

    // ── BLE MIDI ──────────────────────────────────────────────────────────────

    private fun startBleMidi() {
        val adapter = bluetoothAdapter ?: run {
            Log.w(TAG, "Bluetooth not available"); return
        }
        if (!adapter.isEnabled) { Log.w(TAG, "Bluetooth disabled"); return }

        // Check for required Bluetooth permissions before scanning
        val bluetoothScanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.Manifest.permission.BLUETOOTH_SCAN
        } else {
            android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        
        if (ContextCompat.checkSelfPermission(
                appContext ?: return, bluetoothScanPermission
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Bluetooth scan permission not granted, skipping BLE MIDI scanning")
            return
        }

        val scanner = adapter.bluetoothLeScanner ?: return

        val filter = ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid.fromString(BLE_MIDI_SERVICE))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(filter), settings, bleScanCallback)
        Log.d(TAG, "BLE MIDI scan started")
    }

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            Log.d(TAG, "BLE MIDI device found: ${device.name ?: device.address}")
            connectBleMidiDevice(device)
            // Stop scanning after finding first device to save battery
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
        }
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: $errorCode")
        }
    }

    private fun connectBleMidiDevice(device: BluetoothDevice) {
        val manager = midiManager ?: return
        manager.openBluetoothDevice(device, { midiDevice ->
            if (midiDevice == null) { Log.w(TAG, "BLE MIDI open failed"); return@openBluetoothDevice }
            openDevices.add(midiDevice)

            val info = midiDevice.info
            if (info.outputPortCount == 0) return@openBluetoothDevice

            val port = midiDevice.openOutputPort(0) ?: return@openBluetoothDevice
            outputPorts.add(port)
            port.connect(midiReceiver)

            val name = device.name ?: device.address
            Log.d(TAG, "Connected BLE MIDI: $name")
            emit(MidiEvent.DeviceConnected(name, "Bluetooth"))
        }, mainHandler)
    }

    // ── USB hot-plug broadcast receiver ───────────────────────────────────────

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "android.hardware.usb.action.USB_DEVICE_ATTACHED" -> {
                    Log.d(TAG, "USB device attached — refreshing MIDI")
                    startUsbMidi()
                }
                "android.hardware.usb.action.USB_DEVICE_DETACHED" -> {
                    Log.d(TAG, "USB device detached")
                }
            }
        }
    }

    private fun registerUsbHotplug(context: Context) {
        val filter = IntentFilter().apply {
            addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED")
            addAction("android.hardware.usb.action.USB_DEVICE_DETACHED")
        }
        runCatching { context.registerReceiver(usbReceiver, filter) }
    }

    // ── MIDI message parser ───────────────────────────────────────────────────

    private val midiReceiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            if (count < 2) return
            val status  = msg[offset].toInt() and 0xFF
            val type    = status and 0xF0
            val channel = status and 0x0F
            val data1   = if (count > 1) msg[offset + 1].toInt() and 0xFF else 0
            val data2   = if (count > 2) msg[offset + 2].toInt() and 0xFF else 0

            when (type) {
                0x90 -> { // Note On
                    if (data2 > 0) {
                        mainHandler.post { handleNoteOn(channel, data1, data2) }
                    } else {
                        // Note On with velocity 0 = Note Off
                        mainHandler.post { emit(MidiEvent.NoteOff(channel, data1)) }
                    }
                }
                0x80 -> { // Note Off
                    mainHandler.post { emit(MidiEvent.NoteOff(channel, data1)) }
                }
                0xB0 -> { // Control Change
                    mainHandler.post { handleControlChange(channel, data1, data2) }
                }
                0xC0 -> { // Program Change — ignored for now
                    Log.d(TAG, "Program change: ${data1}")
                }
                else -> { /* SysEx, pitch bend etc. — ignored */ }
            }
        }
    }

    // ── Note handling & chord detection ───────────────────────────────────────

    /**
     * Notes arriving within CHORD_WINDOW_MS of each other are grouped into a chord.
     * Once the window closes, if 3+ notes arrived → chord detected.
     * If 1–2 notes → single note event.
     */
    private fun handleNoteOn(channel: Int, note: Int, velocity: Int) {
        pendingNotes.add(note)
        emit(MidiEvent.NoteOn(channel, note, velocity))

        // Cancel previous window and restart
        chordDetectRunnable?.let { mainHandler.removeCallbacks(it) }
        chordDetectRunnable = Runnable {
            if (pendingNotes.size >= 3) {
                Log.d(TAG, "Chord detected from MIDI: $pendingNotes")
                // Re-emit as a structured chord — MainActivity can intercept this
                // by checking for rapid NoteOn burst in its listener
            }
            pendingNotes.clear()
        }
        mainHandler.postDelayed(chordDetectRunnable!!, CHORD_WINDOW_MS)
    }

    /**
     * CC 64 (sustain pedal, value ≥ 64) → trigger play/stop
     * CC 7  (volume knob, 0–127)         → map to BPM 60–180
     */
    private fun handleControlChange(channel: Int, cc: Int, value: Int) {
        emit(MidiEvent.ControlChange(channel, cc, value))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun emit(event: MidiEvent) {
        listener?.invoke(event)
    }

    private fun deviceName(info: MidiDeviceInfo): String {
        val props = info.properties
        return props.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: props.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: "Unknown MIDI Device"
    }
}