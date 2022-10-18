import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import java.util.concurrent.TimeUnit

@Grab(group = 'com.hivemq', module = 'hivemq-mqtt-client', version = '1.3.0')
@Grab(group = 'org.slf4j', module = 'slf4j-simple', version = '1.7.13')


class Env {
    static String mqttHost = "rpi4" // System.getenv("MQTT_HOST")
    static String mqttPort = "1883" // System.getenv("MQTT_PORT")
    static  ZIGBEE_VALUES_REFRESH_PERIOD_SEC = 300      // ako casto sa maju nacitat data zo vsetkych namapovanych zigbee devices
}


class Configuration {
    // main app configuration
    def configuration

    // helpers to speed up searching for mappings
    def zigbeeDeviceNameToMapping = [:]
    def loxoneTopicToMapping = [:]

    def getMappingForLoxonePath(loxonePath) {
        return loxoneTopicToMapping[loxonePath]
    }

    def getMappingForZigbeeDevice(String zigbeeDeviceName) {
        return zigbeeDeviceNameToMapping[zigbeeDeviceName]
    }

    String[] getConfiguredZigbeeDeviceNames() {
        return zigbeeDeviceNameToMapping.keySet()
    }

    void load(File configFile) {
        configuration = new JsonSlurper().parse(configFile)
        configuration.mapping.each { mapping ->
            zigbeeDeviceNameToMapping.put(mapping.zigbeeDeviceName, mapping)
            mapping.l2zPayloadMappings?.each { i ->
                loxoneTopicToMapping.put(i.loxoneTopic, mapping)
            }
        }
    }
}

class MqttCache {

    def loxonePayloads = [:]
    def zigbeePayloads = [:]

    def storeLoxonePayload(loxonePath, payload) {
        loxonePayloads[loxonePath] = payload
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
                def loxonePath = mqttMessage.topic.toString().replaceFirst("loxone/", "")
                def mapping = configuration.getMappingForLoxonePath(loxonePath)
                if (mapping != null && mapping.enabled) {
                    def payload = getPayload(mqttMessage)
                    mqttCache.storeLoxonePayload(loxonePath, payload)
                    mapping.l2zPayloadMappings?.each { m ->
                        def template = engine.createTemplate(m.mappingFormula).make([payload: payload])
                        def zigbeePayload = template.toString()
                        if (zigbeePayload != "null") {
                            sendToZigbeeDevice(mapping.zigbeeDeviceName, zigbeePayload)
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to process loxone mqtt message", e)
        }
    }

    def processZigbeeMqttMessage(Mqtt3Publish mqtt3Publish) {
        try {
            def topic = mqtt3Publish.topic.toString()
            def zigbeeDeviceName = getZigbeeDeviceName(topic)
            if (topic.endsWith(zigbeeDeviceName)) {
                forwardZigbeeToLoxone(mqtt3Publish);
            }
        } catch (Exception e) {
            log.error("Failed to process zigbee mqtt message", e)
        }
    }


    def forwardZigbeeToLoxone(Mqtt3Publish mqtt3Publish) {
        def topic = mqtt3Publish.topic.toString()
        def zigbeeDeviceName = getZigbeeDeviceName(topic)
        def mapping = configuration.getMappingForZigbeeDevice(zigbeeDeviceName)
        if (mapping != null && mapping.enabled) {
            def payload = getPayload(mqtt3Publish)
            mqttCache.storeZigbeePayload(zigbeeDeviceName, payload)

            // transform and send to zigbee
            mapping.z2lPayloadMappings?.each { m ->
                def template = engine.createTemplate(m.mappingFormula).make([payload: payload])
                try {
                    def loxonePayload = template.toString()
                    if (loxonePayload != "null") {
                        sendToLoxoneComponent(m.loxoneTopic, loxonePayload)
                    }
                } catch (Exception e) {
                    log.error("Failed to send to loxone", e)
                }
            }
        }
    }

    String getZigbeeDeviceName(String topic) {
        // zigbee/device_name/status -> device_name
        def splt = topic.split("/")
        return splt[1]
    }

    void sendToZigbeeDevice(String zigbeeDeviceName, String mqttPayload) {
        sendMqttMessage("zigbee/${zigbeeDeviceName}/set", mqttPayload)
    }

    void sendToLoxoneComponent(String loxoneSendPath, String mqttPayload) {
        sendMqttMessage("loxone/${loxoneSendPath}", mqttPayload)
    }

    // for all configured zigbee device mappings, try to get actual data from devices
    void refreshZigbeeData() {
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
                    mqttClient.subscribeWith().topicFilter("loxone/#").callback(this::processLoxoneMqttMessage).send()
                    mqttClient.subscribeWith().topicFilter("zigbee/#").callback(this::processZigbeeMqttMessage).send()
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
                configuration.load("exampleConfiguration.json" as File)
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

// run main loop
new Bridge().run()
