package com.aura.companion.audio

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class SpeechState {
    IDLE, LISTENING, PROCESSING, ERROR
}

/**
 * SpeechManager with continuous-listening mode, on-device recognition,
 * extended silence limits, and chime suppression.
 */
class SpeechManager(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(SpeechState.IDLE)
    val state: StateFlow<SpeechState> = _state

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText

    private var continuousMode = false
    private var isPaused = false
    private var lastSpeechDetectedTime = System.currentTimeMillis()
    private var consecutiveSilenceCount = 0

    private var onSegmentCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    fun initialize() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                try {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } catch (e: Exception) {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            speechRecognizer?.setRecognitionListener(createListener())
        }
    }

    fun startContinuousListening(
        onSegment: (String) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        continuousMode = true
        isPaused = false
        lastSpeechDetectedTime = System.currentTimeMillis()
        onSegmentCallback = onSegment
        onErrorCallback = onError
        startRecognition()
    }

    fun startOneShotListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        continuousMode = false
        isPaused = false
        lastSpeechDetectedTime = System.currentTimeMillis()
        onSegmentCallback = onResult
        onErrorCallback = onError
        startRecognition()
    }

    fun pauseListening() {
        isPaused = true
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.w("SpeechManager", "Error pausing: ${e.message}")
        }
        _state.value = SpeechState.IDLE
    }

    fun resumeListening() {
        if (continuousMode && isPaused) {
            isPaused = false
            lastSpeechDetectedTime = System.currentTimeMillis()
            startRecognition()
        }
    }

    fun stopListening() {
        continuousMode = false
        isPaused = false
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.w("SpeechManager", "Error stopping: ${e.message}")
        }
        _state.value = SpeechState.IDLE
    }

    fun destroy() {
        continuousMode = false
        isPaused = false
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun startRecognition() {
        if (isPaused) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Enable continuous dictation mode to prevent premature session cutoffs
            putExtra("android.speech.extra.DICTATION_MODE", true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 8000L)
            putExtra("android.speech.extras.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 15000L)
            putExtra("android.speech.extras.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 10000L)
            putExtra("android.speech.extras.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 8000L)
        }

        _state.value = SpeechState.LISTENING
        _recognizedText.value = ""

        // Temporarily silence the stream to suppress the start-recording chime
        silenceBeep(true)

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e("SpeechManager", "Failed to start: ${e.message}")
            silenceBeep(false)
            _state.value = SpeechState.ERROR
            if (continuousMode && !isPaused) {
                restartAfterDelay(2000)
            }
        }
    }

    private var isMuted = false

    private fun silenceBeep(mute: Boolean) {
        try {
            if (mute) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                isMuted = true
            } else if (isMuted) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                isMuted = false
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun restartAfterDelay(delayMs: Long) {
        if (!continuousMode || isPaused) return
        mainHandler.postDelayed({
            if (continuousMode && !isPaused) {
                try {
                    speechRecognizer?.cancel()
                } catch (e: Exception) {
                    // Ignore
                }
                startRecognition()
            }
        }, delayMs)
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = SpeechState.LISTENING
            // Unmute system sound after chime window has safely passed
            mainHandler.postDelayed({ silenceBeep(false) }, 600)
        }

        override fun onBeginningOfSpeech() {
            consecutiveSilenceCount = 0
            _state.value = SpeechState.LISTENING
            silenceBeep(false)
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            _state.value = SpeechState.PROCESSING
        }

        override fun onError(error: Int) {
            silenceBeep(false)

            val isSilence = error == SpeechRecognizer.ERROR_NO_MATCH ||
                            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            val isNetwork = error == SpeechRecognizer.ERROR_NETWORK ||
                            error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED

            if (isNetwork) {
                _state.value = SpeechState.IDLE
                isPaused = true
                onErrorCallback?.invoke("Offline: Download offline voice in phone settings")
                return
            }

            if (continuousMode && !isPaused) {
                _state.value = SpeechState.LISTENING
                if (isSilence) {
                    consecutiveSilenceCount++
                    // Back off when room is quiet:
                    // Avoid machine-gunning restarts every 300ms which triggers continuous beeping
                    val silenceDelay = if (consecutiveSilenceCount > 1) 2800L else 1200L
                    restartAfterDelay(silenceDelay)
                } else {
                    restartAfterDelay(1500L)
                }
            } else {
                _state.value = SpeechState.IDLE
                if (!isSilence) {
                    onErrorCallback?.invoke("Error $error")
                }
            }
        }

        override fun onResults(results: Bundle?) {
            silenceBeep(false)
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            _recognizedText.value = text

            if (text.isNotBlank()) {
                consecutiveSilenceCount = 0
                lastSpeechDetectedTime = System.currentTimeMillis()
                Log.d("SpeechManager", "Recognized: $text")
                onSegmentCallback?.invoke(text)
            }

            if (continuousMode && !isPaused) {
                _state.value = SpeechState.LISTENING
                restartAfterDelay(if (text.isNotBlank()) 400L else 1500L)
            } else {
                _state.value = SpeechState.IDLE
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            consecutiveSilenceCount = 0
            lastSpeechDetectedTime = System.currentTimeMillis()
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: ""
            _recognizedText.value = partial
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
