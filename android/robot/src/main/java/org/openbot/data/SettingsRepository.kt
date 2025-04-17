package org.openbot.data

import org.openbot.data.model.MqttConfig
import org.openbot.data.model.TransportType
import javax.inject.Inject
import javax.inject.Singleton

interface SettingsRepository {
    var transportType: TransportType
    var mqttConfig: MqttConfig?
    var webSocketUrl: String?
}

@Singleton
class SettingsRepositoryImpl @Inject constructor() : SettingsRepository {
    override var transportType: TransportType = TransportType.WebSockets
    override var mqttConfig: MqttConfig? = null
    override var webSocketUrl: String? = null
}