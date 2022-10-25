import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import java.util.concurrent.TimeUnit

// BRIDGE_CONFIG_FILE=/home/msk/work/github/loxone/zigbee/zigbee2loxone_bridge/config/bridge_configuration.json;LOXONE_TOPIC=loxone;MQTT_HOST=rpi4;MQTT_PORT=1883;ZIGBEE_TOPIC=zigbee

class Util {
    static String joinTopic(String[] parts) {
        return parts.join("/").replaceAll("//", "/")
    }

    static String toLoxonePath(String mqttTopic) {
        def splt = mqttTopic.split("/")
        return "${splt[1]}/${splt[2]}/${splt[3]}"
    }
}

class Env {
    static String zigbeeTopic = System.getenv("ZIGBEE_TOPIC")
    static String loxoneTopic = System.getenv("LOXONE_TOPIC")
    static String mqttHost = System.getenv("MQTT_HOST")
    static String mqttPort = System.getenv("MQTT_PORT")
    static String configFile = System.getenv("BRIDGE_CONFIG_FILE")
    static ZIGBEE_VALUES_REFRESH_PERIOD_SEC = 300       // how often ask for data of all zigbee devices
}

@Slf4j
class Configuration {
    // main app configuration
    def configuration
    File configFile
    Long configFileMtime

    // helpers to speed up searching for mappings
    def zigbeeDeviceNameToMapping = [:]
    def loxoneTopicToMapping = [:]

    def getMappingForZigbeeDevice(String zigbeeDeviceName) {
        return zigbeeDeviceNameToMapping[zigbeeDeviceName]
    }

    String[] getConfiguredZigbeeDeviceNames() {
        return zigbeeDeviceNameToMapping.keySet()
    }

    def getMappingForLoxoneTopic(String mqttTopic) {
        return loxoneTopicToMapping[mqttTopic]
    }

    void load(File configFile) {
        log.info("Loading config file $configFile")
        if (configFile.isFile() && configFile.size() != 0) {
            configuration = new JsonSlurper().parse(configFile)
            this.configFile = configFile
            configFileMtime = configFile.lastModified()
        } else {
            configuration = [:]
            configuration.mapping = [:]
        }

        zigbeeDeviceNameToMapping = [:]
        loxoneTopicToMapping = [:]
        configuration.mapping.each { mapping ->
            zigbeeDeviceNameToMapping.put(mapping.zigbeeDeviceName, mapping)
            mapping.payloadMapping?.each { i ->
                def topic = Util.joinTopic(Env.loxoneTopic, i.loxoneComponentName, "/state")
                loxoneTopicToMapping.put(topic, mapping)
            }
        }
    }

    void checkConfigFileChange() {
        if(configFile.lastModified() != configFileMtime) {
            log.info("Reloading config file due to content changed")
            load(configFile)
        }
    }
}

class ValueCache {
    def zigbeeCache = [:]
    def loxoneCache = [:]

    def rememberZigbeeValue(deviceName, attributeName, value) {
        zigbeeCache["${deviceName}:${attributeName}"] = value
    }

    def getZigbeeValue(deviceName, attributeName) {
        return zigbeeCache["${deviceName}:${attributeName}"]
    }

    def rememberLoxoneValue(String loxoneComponentName, String value) {
        loxoneCache[loxoneComponentName] = value
    }

    def getLoxoneValue(String loxoneComponentName) {
        return loxoneCache[loxoneComponentName]
    }
}

class MqttCache {

    def loxonePayloads = [:]
    def zigbeePayloads = [:]

    def storeLoxonePayload(mqttTopic, payload) {
        loxonePayloads[mqttTopic] = payload
    }

    def storeZigbeePayload(zigbeeDeviceName, payload) {
        zigbeePayloads[zigbeeDeviceName] = payload
    }

}

// main bridge class
@Slf4j
class Bridge {
    final mqttCache = new MqttCache()
    Mqtt3AsyncClient mqttClient
    boolean mqttClientConnected
    final jsonSlurper = new JsonSlurper()
    def configuration = new Configuration()
    def engine = new groovy.text.GStringTemplateEngine()
    def jsonBuilder = new JsonBuilder()
    private final ValueCache valueCache = new ValueCache()


    def processLoxoneMqttMessage(Mqtt3Publish mqttMessage) {
        try {
            if (mqttMessage.payload.isPresent()) {
                def topic = mqttMessage.topic.toString()
                def mapping = configuration.getMappingForLoxoneTopic(topic)
                if (mapping != null && mapping.enabled && (mapping.direction == 'BIDIRECTIONAL' || mapping.direction == 'LOXONE_TO_ZIGBEE')) {
                    forwardLoxoneToZigbe(mqttMessage, mapping)
                }
            }
        } catch (Exception e) {
            log.error("Failed to process loxone mqtt message", e)
        }
    }

    def processZigbeeMqttMessage(Mqtt3Publish mqttMessage) {
        try {
            def topic = mqttMessage.topic.toString()
            def zigbeeDeviceName = getZigbeeDeviceName(topic)
            if (topic.endsWith(zigbeeDeviceName)) {
                def mapping = configuration.getMappingForZigbeeDevice(zigbeeDeviceName)
                if (mapping != null && mapping.enabled && (mapping.direction == 'BIDIRECTIONAL' || mapping.direction == 'ZIGBEE_TO_LOXONE')) {
                    forwardZigbeeToLoxone(mqttMessage, mapping);
                }
            }
        } catch (Exception e) {
            log.error("Failed to process zigbee mqtt message", e)
        }
    }


    def forwardLoxoneToZigbe(Mqtt3Publish mqttMessage, mapping) {
        def payload = getPayload(mqttMessage)
        mqttCache.storeLoxonePayload(mqttMessage.topic.toString(), payload)
        mapping.payloadMapping?.each { m ->
            def zigbeeValue = tryApplyFormula(payload[m.loxoneAttributeName], m.mappingFormulaL2Z)
            if(zigbeeValue != null) {
                sendToZigbeeDevice(mapping.zigbeeDeviceName, m.zigbeeAttributeName, zigbeeValue.toString())
            }
        }

    }

    def forwardZigbeeToLoxone(Mqtt3Publish mqttMessage, mapping) {
        def topic = mqttMessage.topic.toString()
        def zigbeeDeviceName = getZigbeeDeviceName(topic)
        def payload = getPayload(mqttMessage)
        mqttCache.storeZigbeePayload(zigbeeDeviceName, payload)
        // transform and send to zigbee
        mapping.payloadMapping?.each { m ->
            def loxonePayload = tryApplyFormula(payload[m.zigbeeAttributeName], m.mappingFormulaZ2L)
            try {
                if(loxonePayload != null) {
                    sendToLoxoneComponent(m.loxoneComponentName, loxonePayload.toString())
                }
            } catch (Exception e) {
                log.error("Failed to send to loxone", e)
            }

        }

    }

    String getZigbeeDeviceName(String topic) {
        // zigbee/device_name/status -> device_name
        def splt = topic.split("/")
        return splt[1]
    }

    def sendToZigbeeDevice(String zigbeeDeviceName, String zigbeeAttributeName, String value) {
        // debounce
        if (value.equals(valueCache.getZigbeeValue(zigbeeDeviceName, zigbeeAttributeName))) {
            return;
        }
        valueCache.rememberZigbeeValue(zigbeeDeviceName, zigbeeAttributeName, value)
        sendMqttMessage("${Env.zigbeeTopic}/${zigbeeDeviceName}/set", """{"${zigbeeAttributeName}":"${value}"}""")
    }

    // for all configured zigbee device mappings, try to get actual data from devices
    def sendToLoxoneComponent(String loxoneComponentName, String value) {
        // debounce
        if (value.equals(valueCache.getLoxoneValue(loxoneComponentName))) {
            return;
        }
        valueCache.rememberLoxoneValue(loxoneComponentName, value)
        sendMqttMessage("${Env.loxoneTopic}/${loxoneComponentName}/cmd", value)
    }

    def refreshZigbeeData() {
        if (mqttClientConnected) {
            configuration.getConfiguredZigbeeDeviceNames().each { deviceName ->
                sendMqttMessage("zigbee/${deviceName}/get", '{"state": ""}')
            }
        }
    }

    def sendMqttMessage(String topic, String payload) {
        log.info("Sending mqtt message to topic '{}', payload '{}'", topic, payload)
        mqttClient.publishWith()
                .topic(topic)
                .payload(payload.getBytes())
                .send()
    }

    def connect() {
        log.info("Connecting to mqtt broker")
        int rnd = (Math.random() * 100) as int
        mqttClient = MqttClient.builder()
                .useMqttVersion3()
                .identifier("zigbee2loxone_bridge_${rnd}")
                .serverHost(Env.mqttHost)
                .serverPort(Env.mqttPort as Integer)
                .addConnectedListener {
                    mqttClientConnected = true
                    log.info("MQTT connected")
                    mqttClient.subscribeWith().topicFilter("${Env.loxoneTopic}/#").callback(this::processLoxoneMqttMessage).send()
                    mqttClient.subscribeWith().topicFilter("${Env.zigbeeTopic}/#").callback(this::processZigbeeMqttMessage).send()
                    refreshZigbeeData()
                }
                .addDisconnectedListener {
                    mqttClientConnected = false
                    log.info("MQTT disconnected")
                }
                .automaticReconnect().initialDelay(30, TimeUnit.SECONDS).applyAutomaticReconnect()
                .buildAsync();

        mqttClient.connect().get(60, TimeUnit.SECONDS)
        log.info("Initialized")

        while (mqttClientConnected) {
            sleep(Env.ZIGBEE_VALUES_REFRESH_PERIOD_SEC * 1000)
            refreshZigbeeData()
        }
    }

    void run() {
        while (true) {
            try {
                configuration.load(Env.configFile as File)
                def configReloadTimer = new Timer()
                configReloadTimer.scheduleAtFixedRate(()->{
                    configuration.checkConfigFileChange()
                }, 10000, 10000)
                connect()
            } catch (Exception e) {
                log.error("Failed to start bridge, reason: {}", e.toString())
                sleep(3000)
            }
        }
    }

    def getPayload(Mqtt3Publish mqtt3Publish) {
        byte[] buffer = new byte[mqtt3Publish.payload.get().remaining()];
        mqtt3Publish.payload.get().get(buffer);
        try {
            return jsonSlurper.parse(buffer)
        } catch (Exception e) {
            return new String(buffer)
        }
    }

    def tryApplyFormula(value, formula) {
        if(value == null) {
            return null
        }
        if (formula == null || formula.isEmpty()) {
            return value
        }
        def template = engine.createTemplate(formula).make([value: value])
        def converted = template.toString()
        return (converted == null || converted == "null") ? value : converted
    }
}

static void main(String[] args) {
    assert Env.zigbeeTopic != null
    assert Env.loxoneTopic != null
    assert Env.mqttHost != null
    assert Env.mqttPort != null
    assert Env.configFile != null

    // run main loop
    new Bridge().run()
}

