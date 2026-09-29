package com.aura.companion.capture

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Offline speech pipeline from sherpa-onnx: Silero VAD cuts the mic stream into utterances and
 * Parakeet TDT transcribes each one. Models ship in the APK under assets/asr/ (see build.gradle.kts).
 *
 * The VAD half ([acceptAudio], [flush], [reset], [isSpeech]) must be driven from one thread and
 * [transcribe] from one thread; the two halves are independent of each other.
 */
class AsrEngine private constructor(
    private val vad: Vad,
    private val recognizer: OfflineRecognizer
) {

    companion object {
        const val SAMPLE_RATE = 16_000
        const val VAD_WINDOW = 512 // Silero expects 512-sample windows at 16 kHz

        fun create(assets: AssetManager): AsrEngine {
            val vadConfig = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "asr/silero_vad.onnx",
                    threshold = 0.5f,
                    minSilenceDuration = 0.8f, // pause length that ends an utterance
                    minSpeechDuration = 0.25f,
                    windowSize = VAD_WINDOW,
                    maxSpeechDuration = 20f
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1
            )
            val recognizerConfig = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = "asr/encoder.int8.onnx",
                        decoder = "asr/decoder.int8.onnx",
                        joiner = "asr/joiner.int8.onnx"
                    ),
                    tokens = "asr/tokens.txt",
                    modelType = "nemo_transducer",
                    numThreads = 2
                ),
                decodingMethod = "greedy_search"
            )
            val vad = Vad(assets, vadConfig)
            val recognizer = try {
                OfflineRecognizer(assets, recognizerConfig)
            } catch (e: Throwable) {
                vad.release()
                throw e
            }
            return AsrEngine(vad, recognizer)
        }
    }

    /** Feeds one window of 16 kHz mono audio; returns any utterances the VAD has closed. */
    fun acceptAudio(samples: FloatArray): List<FloatArray> {
        vad.acceptWaveform(samples)
        return drain()
    }

    /** Closes the utterance in progress, if any (used when the mic stops). */
    fun flush(): List<FloatArray> {
        vad.flush()
        return drain()
    }

    fun reset() = vad.reset()

    val isSpeech: Boolean get() = vad.isSpeechDetected()

    fun transcribe(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    fun release() {
        vad.release()
        recognizer.release()
    }

    private fun drain(): List<FloatArray> {
        if (vad.empty()) return emptyList()
        val segments = mutableListOf<FloatArray>()
        while (!vad.empty()) {
            segments.add(vad.front().samples)
            vad.pop()
        }
        return segments
    }
}
