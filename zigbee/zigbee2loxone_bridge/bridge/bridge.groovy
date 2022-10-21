import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import java.util.concurrent.TimeUnit

@GrabConfig(systemClassLoader=true)     // for slf4j in docker image, dunno why it doesnt work without this setting
@Grab(group = 'com.hivemq', module = 'hivemq-mqtt-client', version = '1.3.0')
@Grab(group = 'org.slf4j', module = 'slf4j-simple', version = '1.7.36')

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


class Configuration {
    // main app configuration
    def configuration

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
        if(configFile.isFile() && configFile.size() != 0) {
            configuration = new JsonSlurper().parse(configFile)
        } else {
            configuration = [:]
            configuration.mapping = [:]
        }
        configuration.mapping.each { mapping ->
            zigbeeDeviceNameToMapping.put(mapping.zigbeeDeviceName, mapping)
            mapping.l2zPayloadMappings?.each { i ->
                def topic = Util.joinTopic(Env.loxoneTopic, i.loxoneComponentName, "/state")
                loxoneTopicToMapping.put(topic, mapping)
            }
        }
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


    def processLoxoneMqttMessage(Mqtt3Publish mqttMessage) {
        try {
            if (mqttMessage.payload.isPresent()) {
                def topic = mqttMessage.topic.toString()
                def mapping = configuration.getMappingForLoxoneTopic(topic)
                if (mapping != null && mapping.enabled) {
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
                if(mapping != null && mapping.enabled) {
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
        mapping.l2zPayloadMappings?.each { m ->
            def template = engine.createTemplate(m.mappingFormula).make([payload: payload])
            def zigbeePayload = template.toString()
            if (zigbeePayload != "null") {
                sendToZigbeeDevice(mapping.zigbeeDeviceName, zigbeePayload)
            }
        }

    }

    def forwardZigbeeToLoxone(Mqtt3Publish mqttMessage, mapping) {
        def topic = mqttMessage.topic.toString()
        def zigbeeDeviceName = getZigbeeDeviceName(topic)
        def payload = getPayload(mqttMessage)
        mqttCache.storeZigbeePayload(zigbeeDeviceName, payload)
        // transform and send to zigbee
        mapping.z2lPayloadMappings?.each { m ->
            def template = engine.createTemplate(m.mappingFormula).make([payload: payload])
            try {
                def loxonePayload = template.toString()
                if (loxonePayload != "null") {
                    sendToLoxoneComponent(m.loxoneComponentName, loxonePayload)
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

    def sendToZigbeeDevice(String zigbeeDeviceName, String mqttPayload) {
        sendMqttMessage("${Env.zigbeeTopic}/${zigbeeDeviceName}/set", mqttPayload)
    }

    // for all configured zigbee device mappings, try to get actual data from devices
    def sendToLoxoneComponent(String loxoneComponentName, String mqttPayload) {
        sendMqttMessage("${Env.loxoneTopic}/${loxoneComponentName}/cmd", mqttPayload)
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

