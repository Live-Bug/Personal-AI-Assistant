package com.aura.companion.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class CaptureState {
    STOPPED,   // listening is off
    STARTING,  // loading the speech model / opening the mic
    LISTENING,
    ERROR
}

/** One transcribed stretch of speech, with wall-clock times. */
data class Utterance(val text: String, val startedAt: Long, val endedAt: Long)

/**
 * Owns the microphone for always-on listening. Audio is read continuously (16 kHz mono), cut
 * into utterances by the VAD and transcribed on-device; nothing is recorded to disk.
 * Started and stopped by AuraForegroundService.
 */
class CaptureController(private val context: Context, private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "CaptureController"
    }

    private val _state = MutableStateFlow(CaptureState.STOPPED)
    val state: StateFlow<CaptureState> = _state

    // True while the VAD hears speech right now
    private val _hearingSpeech = MutableStateFlow(false)
    val hearingSpeech: StateFlow<Boolean> = _hearingSpeech

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _utterances = MutableSharedFlow<Utterance>(extraBufferCapacity = 64)
    val utterances: SharedFlow<Utterance> = _utterances

    // Emitted after the last utterance of a listening session has been delivered
    private val _sessionEnded = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val sessionEnded: SharedFlow<Unit> = _sessionEnded

    private sealed interface Captured {
        class Audio(val samples: FloatArray, val endedAt: Long) : Captured
        object EndOfSession : Captured
    }

    private val lock = Any()
    private var running = false // guarded by lock
    private var micJob: Job? = null // guarded by lock
    @Volatile private var micRunning = false

    private val engineMutex = Mutex()
    @Volatile private var engine: AsrEngine? = null

    // Transcription runs off the mic thread so a slow decode never drops audio
    private val captured = Channel<Captured>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.Default) {
            for (item in captured) handle(item)
        }
    }

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            _error.value = null
            val previous = micJob
            micJob = scope.launch(Dispatchers.IO) {
                previous?.join() // never hold two AudioRecords at once
                runMic()
            }
        }
        refreshState()
    }

    fun stop() {
        synchronized(lock) {
            running = false
            micJob?.cancel()
        }
        refreshState()
    }

    private fun refreshState() {
        _state.value = synchronized(lock) {
            when {
                !running -> CaptureState.STOPPED
                _error.value != null && !micRunning -> CaptureState.ERROR
                micRunning -> CaptureState.LISTENING
                else -> CaptureState.STARTING
            }
        }
    }

    private suspend fun loadEngine(): AsrEngine = engineMutex.withLock {
        engine ?: AsrEngine.create(context.assets).also {
            engine = it
            Log.i(TAG, "Speech models loaded")
        }
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        _error.value = message
        refreshState()
    }

    private suspend fun runMic() {
        val asr = try {
            loadEngine()
        } catch (e: Throwable) {
            fail("Speech model failed to load: ${e.message}")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            fail("Microphone permission not granted")
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            AsrEngine.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                AsrEngine.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, AsrEngine.SAMPLE_RATE * 2 * 2) // ~2 s of headroom
            )
        } catch (e: SecurityException) {
            fail("Microphone permission not granted")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            fail("Microphone unavailable")
            return
        }

        val buffer = ShortArray(AsrEngine.VAD_WINDOW)
        try {
            record.startRecording()
            micRunning = true
            _error.value = null
            refreshState()
            Log.i(TAG, "Microphone open")

            while (currentCoroutineContext().isActive) {
                val read = record.read(buffer, 0, buffer.size)
                if (read < 0) {
                    fail("Microphone read error ($read)")
                    break
                }
                if (read == 0) continue
                val samples = FloatArray(read) { buffer[it] / 32768f }
                for (segment in asr.acceptAudio(samples)) {
                    captured.trySend(Captured.Audio(segment, System.currentTimeMillis()))
                }
                _hearingSpeech.value = asr.isSpeech
            }
        } finally {
            micRunning = false
            try {
                record.stop()
            } catch (e: IllegalStateException) {
                // never started
            }
            record.release()
            // Keep the sentence that was in progress when the mic stopped
            for (segment in asr.flush()) {
                captured.trySend(Captured.Audio(segment, System.currentTimeMillis()))
            }
            asr.reset()
            captured.trySend(Captured.EndOfSession)
            _hearingSpeech.value = false
            refreshState()
            Log.i(TAG, "Microphone closed")
        }
    }

    private suspend fun handle(item: Captured) {
        when (item) {
            is Captured.EndOfSession -> _sessionEnded.emit(Unit)
            is Captured.Audio -> {
                val asr = engine ?: return
                val startedAt = item.endedAt - item.samples.size * 1000L / AsrEngine.SAMPLE_RATE
                val text = try {
                    asr.transcribe(item.samples)
                } catch (e: Throwable) {
                    Log.e(TAG, "Transcription failed: ${e.message}")
                    return
                }
                if (text.none { it.isLetterOrDigit() }) return
                _utterances.emit(Utterance(text, startedAt, item.endedAt))
            }
        }
    }
}
