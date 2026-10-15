package dev.retrobit.mirror.host

import dev.retrobit.mirror.input.InputHub
import dev.retrobit.mirror.model.HostUiState
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

interface StreamSink {
    fun onVideoConfig(width: Int, height: Int, csd: ByteArray)
    fun onVideo(data: ByteArray, keyframe: Boolean, ptsUs: Long)
    fun onAudioConfig(csd: ByteArray, sampleRate: Int, channels: Int)
    fun onAudio(data: ByteArray, ptsUs: Long)
    fun onState(json: String)
}

object HostRuntime {
    val state = MutableStateFlow(HostUiState())

    @Volatile
    var session: HostSession? = null

    fun update(block: (HostUiState) -> HostUiState) {
        state.value = block(state.value)
    }
}

class HostSession(
    val pin: String,
    val hostName: String
) {
    val sinks = CopyOnWriteArrayList<StreamSink>()
    val tokens = HashSet<String>()

    @Volatile
    var csd: ByteArray? = null

    @Volatile
    var videoW = 0

    @Volatile
    var videoH = 0

    @Volatile
    var audioCsd: ByteArray? = null

    @Volatile
    var audioRate = 48000

    @Volatile
    var audioChannels = 2

    var requestKeyframe: (() -> Unit)? = null

    @Synchronized
    fun issueToken(): String {
        val token = java.util.UUID.randomUUID().toString().replace("-", "").take(24)
        tokens.add(token)
        return token
    }

    @Synchronized
    fun tokenOk(token: String?): Boolean = !token.isNullOrBlank() && tokens.contains(token)

    fun addSink(sink: StreamSink) {
        sinks.add(sink)
        val config = csd
        if (config != null && videoW > 0) sink.onVideoConfig(videoW, videoH, config)
        val audio = audioCsd
        if (audio != null) sink.onAudioConfig(audio, audioRate, audioChannels)
        sink.onState(stateJson())
    }

    fun removeSink(sink: StreamSink) {
        sinks.remove(sink)
    }

    fun publishConfig(width: Int, height: Int, config: ByteArray) {
        videoW = width
        videoH = height
        csd = config
        sinks.forEach { runCatching { it.onVideoConfig(width, height, config) } }
    }

    fun publishVideo(data: ByteArray, keyframe: Boolean, ptsUs: Long) {
        sinks.forEach { runCatching { it.onVideo(data, keyframe, ptsUs) } }
    }

    fun publishAudioConfig(config: ByteArray, rate: Int, channels: Int) {
        audioCsd = config
        audioRate = rate
        audioChannels = channels
        sinks.forEach { runCatching { it.onAudioConfig(config, rate, channels) } }
    }

    fun publishAudio(data: ByteArray, ptsUs: Long) {
        sinks.forEach { runCatching { it.onAudio(data, ptsUs) } }
    }

    fun publishState() {
        val json = stateJson()
        sinks.forEach { runCatching { it.onState(json) } }
    }

    fun requestKey() {
        requestKeyframe?.invoke()
    }

    fun welcome(ok: Boolean, error: String? = null): String {
        val ui = HostRuntime.state.value
        return JSONObject()
            .put("ok", ok)
            .put("error", error ?: "")
            .put("name", hostName)
            .put("videoW", videoW)
            .put("videoH", videoH)
            .put("displayW", InputHub.displayW)
            .put("displayH", InputHub.displayH)
            .put("injection", InputHub.injectionName())
            .put("audio", audioCsd != null)
            .put("httpPort", ui.httpPort)
            .put("tcpPort", ui.tcpPort)
            .toString()
    }

    fun stateJson(): String {
        val ui = HostRuntime.state.value
        return JSONObject()
            .put("type", "state")
            .put("videoW", videoW)
            .put("videoH", videoH)
            .put("displayW", InputHub.displayW)
            .put("displayH", InputHub.displayH)
            .put("injection", InputHub.injectionName())
            .put("fps", ui.fps)
            .put("clients", ui.clients.size)
            .toString()
    }
}
