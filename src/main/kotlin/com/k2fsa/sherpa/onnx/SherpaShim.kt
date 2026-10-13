package com.k2fsa.sherpa.onnx

class EndpointRule(val mustContainNonSilence: Boolean, val minTrailingSilence: Float, val minUtteranceLength: Float)
class EndpointConfig(val rule1: EndpointRule, val rule2: EndpointRule, val rule3: EndpointRule)
class OnlineTransducerModelConfig(val encoder: String = "", val decoder: String = "", val joiner: String = "")
class OnlineModelConfig(val transducer: OnlineTransducerModelConfig = OnlineTransducerModelConfig(), val tokens: String = "", val numThreads: Int = 2, val provider: String = "cpu")
class OnlineRecognizerConfig(val modelConfig: OnlineModelConfig = OnlineModelConfig(), val endpointConfig: EndpointConfig = EndpointConfig(EndpointRule(false, 2f, 0f), EndpointRule(true, 0.8f, 0f), EndpointRule(false, 0f, 15f)), val enableEndpoint: Boolean = true, val decodingMethod: String = "greedy_search")

class OnlineStream {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int) {}
    fun inputFinished() {}
    fun release() {}
}

class OnlineRecognizerResult(val text: String = "", val ysProbs: FloatArray = FloatArray(0))

class OnlineRecognizer(val config: OnlineRecognizerConfig = OnlineRecognizerConfig()) {
    fun createStream(): OnlineStream = OnlineStream()
    fun isReady(stream: OnlineStream): Boolean = false
    fun decode(stream: OnlineStream) {}
    fun getResult(stream: OnlineStream): OnlineRecognizerResult = OnlineRecognizerResult()
    fun isEndpoint(stream: OnlineStream): Boolean = false
    fun reset(stream: OnlineStream) {}
    fun release() {}
}

class OfflineWhisperModelConfig(val encoder: String = "", val decoder: String = "", val language: String = "", val task: String = "transcribe", val tailPaddings: Int = 1000)
class OfflineModelConfig(val whisper: OfflineWhisperModelConfig = OfflineWhisperModelConfig(), val tokens: String = "", val numThreads: Int = 2, val provider: String = "cpu")
class OfflineRecognizerConfig(val modelConfig: OfflineModelConfig = OfflineModelConfig())

class OfflineStream {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int) {}
    fun release() {}
}

class OfflineRecognizerResult(val text: String = "")

class OfflineRecognizer(val config: OfflineRecognizerConfig = OfflineRecognizerConfig()) {
    fun createStream(): OfflineStream = OfflineStream()
    fun decode(stream: OfflineStream) {}
    fun getResult(stream: OfflineStream): OfflineRecognizerResult = OfflineRecognizerResult()
    fun release() {}
}

class SileroVadModelConfig(val model: String = "", val threshold: Float = 0.5f, val minSilenceDuration: Float = 0.4f, val minSpeechDuration: Float = 0.25f, val windowSize: Int = 512, val maxSpeechDuration: Float = 25f)
class VadModelConfig(val sileroVadModelConfig: SileroVadModelConfig = SileroVadModelConfig(), val sampleRate: Int = 16000, val numThreads: Int = 1, val provider: String = "cpu")
class SpeechSegment(val start: Int, val samples: FloatArray)

class Vad(val config: VadModelConfig = VadModelConfig()) {
    fun empty(): Boolean = true
    fun front(): SpeechSegment = SpeechSegment(0, FloatArray(0))
    fun pop() {}
    fun acceptWaveform(samples: FloatArray) {}
    fun flush() {}
    fun release() {}
}

class OfflineSpeakerSegmentationPyannoteModelConfig(val model: String = "")
class OfflineSpeakerSegmentationModelConfig(val pyannote: OfflineSpeakerSegmentationPyannoteModelConfig = OfflineSpeakerSegmentationPyannoteModelConfig(), val numThreads: Int = 2, val provider: String = "cpu")
class SpeakerEmbeddingExtractorConfig(val model: String = "", val numThreads: Int = 2, val provider: String = "cpu")
class FastClusteringConfig(val numClusters: Int = -1, val threshold: Float = 0.5f)
class OfflineSpeakerDiarizationConfig(val segmentation: OfflineSpeakerSegmentationModelConfig, val embedding: SpeakerEmbeddingExtractorConfig, val clustering: FastClusteringConfig, val minDurationOn: Float = 0.3f, val minDurationOff: Float = 0.5f)
class OfflineSpeakerDiarizationSegment(val start: Float, val end: Float, val speaker: Int)

class OfflineSpeakerDiarization(val config: OfflineSpeakerDiarizationConfig) {
    fun process(samples: FloatArray): Array<OfflineSpeakerDiarizationSegment> = emptyArray()
    fun release() {}
}
