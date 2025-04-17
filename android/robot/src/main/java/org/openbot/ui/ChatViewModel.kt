package org.openbot.ui

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
//import androidx.lifecycle.ViewModel
//import androidx.lifecycle.viewModelScope
//import dagger.hilt.android.lifecycle.HiltViewModel
//import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openbot.AudioRecorder
import org.openbot.OpusDecoder
import org.openbot.OpusEncoder
import org.openbot.OpusStreamPlayer
import org.openbot.data.SettingsRepository
import org.openbot.data.model.DeviceInfo
import org.openbot.data.model.TransportType
import org.openbot.protocol.AbortReason
import org.openbot.protocol.ListeningMode
import org.openbot.protocol.Protocol
import org.openbot.protocol.WebsocketProtocol
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import androidx.lifecycle.ViewModel
import org.openbot.data.SettingsRepositoryImpl
import org.openbot.data.model.Application
import org.openbot.data.model.Board
import org.openbot.data.model.ChipInfo
import org.openbot.data.model.OTA

//@HiltViewModel
@RequiresApi(Build.VERSION_CODES.M)
class ChatViewModel @Inject constructor(
    //@ApplicationContext private val context: Context,
    //@NavigationEvents private val navigationEvents: MutableSharedFlow<String>,
    val deviceInfo: DeviceInfo?,
    val settings: SettingsRepository?
) : ViewModel() {
    companion object {
        private const val TAG = "ChatViewModel"
    }

    constructor() : this(null, null) {

    }

    private var protocol: Protocol? = null

    val display = Display()
    var encoder: OpusEncoder? = null
    var decoder: OpusDecoder? = null
    var recorder: AudioRecorder? = null
    var player: OpusStreamPlayer? = null
    var aborted: Boolean = false
    var keepListening: Boolean = true
    val deviceStateFlow = MutableStateFlow(DeviceState.IDLE)
    var deviceState: DeviceState
        get() = deviceStateFlow.value
        set(value) {
            deviceStateFlow.value = value
        }

    fun initialize(deviceInfo: DeviceInfo?, settings: SettingsRepository?) {
        var deviceInfo2 = deviceInfo
        var settings2 = settings
        if (deviceInfo == null) {
            deviceInfo2 = DeviceInfo(1, 2, 3, 4,
                "", "", "",
                ChipInfo(1, 2, 3, 5),
                Application("", "", "", "", ""),
                emptyList(), OTA(""), Board("", "", emptyList(), "", "")
            )
        }
        if (settings == null) {
            settings2 = SettingsRepositoryImpl().apply {
                this.webSocketUrl = "ws://192.168.101.14:8091/ws/xiaozhi/v1/"
            }
        }

        protocol = initProtocol(deviceInfo2, settings2)

        deviceState = DeviceState.STARTING

        //viewModelScope.launch {
        GlobalScope.launch {
            //FIXME start before checking the version
            protocol!!.start()
            deviceState = DeviceState.CONNECTING
            if (protocol!!.openAudioChannel()) {
                protocol!!.sendStartListening(ListeningMode.AUTO_STOP)
                launch(Dispatchers.IO) {
                    val sampleRate = 16000
                    val channels = 1
                    val frameSizeMs = 60
                    player = OpusStreamPlayer(sampleRate, channels, frameSizeMs)
                    decoder = OpusDecoder(sampleRate, channels, frameSizeMs)
                    player?.start(protocol!!.incomingAudioFlow.map {
                        deviceState = DeviceState.SPEAKING
                        decoder?.decode(it)
                    })
                }
            } else {
                Log.e("WS", "Failed to open audio channel")
            }
            delay(1000)
            var i = 0
            // dummy opus audio bytearray
            launch {
                val sampleRate = 16000
                val channels = 1
                val frameSizeMs = 60
                encoder = OpusEncoder(sampleRate, channels, frameSizeMs)
                recorder = AudioRecorder(sampleRate, channels, frameSizeMs)
                val audioFlow = recorder?.startRecording()
                val opusFlow = audioFlow?.map { encoder?.encode(it) }
                deviceState = DeviceState.LISTENING
                opusFlow?.collect {
                    it?.let { protocol!!.sendAudio(it) }
                }
            }

            launch {
                protocol!!.incomingJsonFlow.collect { json ->
                    val type = json.optString("type")
                    when (type) {
                        "tts" -> {
                            val state = json.optString("state")
                            when (state) {
                                "start" -> {
                                    schedule {
                                        aborted = false
                                        if (deviceState == DeviceState.IDLE || deviceState == DeviceState.LISTENING) {
                                            deviceState = DeviceState.SPEAKING
                                        }
                                    }
                                }

                                "stop" -> {
                                    schedule {
                                        if (deviceState == DeviceState.SPEAKING) {
                                            Log.i(TAG, "waiting for TTS to stop")
                                            player?.waitForPlaybackCompletion()
                                            Log.i(TAG, "TTS stopped")
                                            if (keepListening) {
                                                protocol!!.sendStartListening(ListeningMode.AUTO_STOP)
                                                deviceState = DeviceState.LISTENING
                                            } else {
                                                deviceState = DeviceState.IDLE
                                            }
                                        }
                                    }
                                }

                                "sentence_start" -> {
                                    val text = json.optString("text")
                                    if (text.isNotEmpty()) {
                                        Log.i(TAG, "<< $text")
                                        schedule {
                                            display.setChatMessage("assistant", text)
                                        }
                                    }
                                }
                            }
                        }

                        "stt" -> {
                            val text = json.optString("text")
                            if (text.isNotEmpty()) {
                                Log.i(TAG, ">> $text")
                                schedule {
                                    display.setChatMessage("user", text)
                                }
                            }
                        }

                        "llm" -> {
                            val emotion = json.optString("emotion")
                            if (emotion.isNotEmpty()) {
                                schedule {
                                    display.setEmotion(emotion)
                                }
                            }
                            val script = json.optString("script")
                            if (emotion.isNotEmpty()) {
                                schedule {
                                    executeScript(script);
                                }
                            }
                        }

                        "iot" -> {
                            val commands = json.optJSONArray("commands")
                            Log.i(TAG, "IOT commands: $commands")
//                            if (commands != null) {
//                                val thingManager = iot.ThingManager.getInstance()
//                                for (i in 0 until commands.length()) {
//                                    val command = commands.getJSONObject(i)
//                                    thingManager.invoke(command)
//                                }
//                            }
                        }
                    }
                }
            }
        }
    }

    private fun executeScript(script: String) {
        TODO("Not yet implemented")
    }

    private fun initProtocol(
        deviceInfo: DeviceInfo?,
        settings: SettingsRepository?
    ) = when (settings?.transportType) {

        TransportType.MQTT -> {
                throw NotImplementedError()
                //MqttProtocol(context, settings.mqttConfig!!)
            }

        TransportType.WebSockets -> {
                WebsocketProtocol(deviceInfo!!, settings.webSocketUrl!!, "test-token")
            }

        else -> throw NotImplementedError()
    }

    fun toggleChatState() {
        GlobalScope.launch {
            when (deviceState) {
                DeviceState.ACTIVATING -> {
                    reboot()
                }

                DeviceState.IDLE -> {
                    if (protocol!!.openAudioChannel()) {
                        keepListening = true
                        protocol!!.sendStartListening(ListeningMode.AUTO_STOP)
                        deviceState = DeviceState.LISTENING
                    } else {
                        deviceState = DeviceState.IDLE
                    }
                }

                DeviceState.SPEAKING -> {
                    abortSpeaking(AbortReason.NONE)
                }

                DeviceState.LISTENING -> {
                    protocol!!.closeAudioChannel()
                }

                else -> {
                    Log.e(TAG, "Protocol not initialized or invalid state")
                }
            }
        }
    }

    fun startListening() {
        //viewModelScope.launch {
        GlobalScope.launch {
            if (deviceState == DeviceState.ACTIVATING) {
                reboot()
                return@launch
            }

            keepListening = false
            if (deviceState == DeviceState.IDLE) {
                if (!protocol!!.isAudioChannelOpened()) {
                    deviceState = DeviceState.CONNECTING
                    if (!protocol!!.openAudioChannel()) {
                        deviceState = DeviceState.IDLE
                        return@launch
                    }
                }
                protocol!!.sendStartListening(ListeningMode.MANUAL)
                deviceState = DeviceState.LISTENING
            } else if (deviceState == DeviceState.SPEAKING) {
                abortSpeaking(AbortReason.NONE)
                protocol!!.sendStartListening(ListeningMode.MANUAL)
                delay(120) // Wait for the speaker to empty the buffer
                deviceState = DeviceState.LISTENING
            }
        }
    }

    private fun reboot() {
        // Implement the reboot logic here
    }

    fun abortSpeaking(reason: AbortReason) {
        Log.i(TAG, "Abort speaking")
        aborted = true
        //viewModelScope.launch {
        GlobalScope.launch {
            protocol!!.sendAbortSpeaking(reason)
        }
    }
    private fun schedule(task: suspend () -> Unit) {
        //viewModelScope.launch {
        GlobalScope.launch {
            task()
        }
    }


    fun stopListening() {
        //viewModelScope.launch {
        GlobalScope.launch {
            if (deviceState == DeviceState.LISTENING) {
                protocol!!.sendStopListening()
                deviceState = DeviceState.IDLE
            }
        }
    }

    override fun onCleared() {
        protocol!!.dispose()
        encoder?.release()
        decoder?.release()
        player?.stop()
        recorder?.stopRecording()
        //super.onCleared()
    }
}

enum class DeviceState {
    UNKNOWN,
    STARTING,
    WIFI_CONFIGURING,
    IDLE,
    CONNECTING,
    LISTENING,
    SPEAKING,
    UPGRADING,
    ACTIVATING,
    FATAL_ERROR
}


class Display {
    val chatFlow = MutableStateFlow<List<Message>>(listOf())
    val emotionFlow = MutableStateFlow<String>("neutral")
    fun setChatMessage(sender: String, message: String) {
        val currentList = chatFlow.value
        val replaceStt = true
        if (replaceStt && sender == "user" && currentList.lastOrNull()?.sender == "user") {
            // 创建新列表并更新最后一条用户消息
            chatFlow.value = currentList.toMutableList().apply {
                set(size - 1, this[size - 1].copy(message = message))
            }
        } else {
            // 没有用户消息，或新消息非用户消息，直接添加
            chatFlow.value += Message(sender, message)
        }
    }

    fun setEmotion(emotion: String) {
        emotionFlow.value = emotion
    }
}

val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

data class Message(
    val sender: String = "",
    val message: String = "",
    val nowInString: String = df.format(System.currentTimeMillis())
)
