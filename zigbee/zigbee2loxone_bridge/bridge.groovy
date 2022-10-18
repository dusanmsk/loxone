@GrabResolver(name = 'Paho', root = 'https://repo.eclipse.org/content/repositories/paho-releases/')
@Grab(group = 'org.eclipse.paho', module = 'org.eclipse.paho.client.mqttv3', version = '1.2.0')
@Grab(group = 'org.slf4j', module = 'slf4j-simple', version = '1.7.13')

import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

import java.util.concurrent.CountDownLatch

class Env {
    static String mqttHost = "rpi4" // System.getenv("MQTT_HOST")
    static String mqttPort = "1883" // System.getenv("MQTT_PORT")
    static  ZIGBEE_VALUES_REFRESH_PERIOD = 120000      // ako casto sa maju nacitat data zo vsetkych namapovanych zigbee devices
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
        configuration.mapping.each {mapping->
            zigbeeDeviceNameToMapping.put(mapping.zigbeeDeviceName, mapping)
            mapping.l2zPayloadMappings?.each {i->
                loxoneTopicToMapping.put(i.loxoneTopic, mapping)
            }
        }
    }
}

class MqttCache {

    def loxonePayloads = [:]
    def zigbeePayloads = [:]

    def  storeLoxonePayload( loxonePath,  payload) {
        loxonePayloads[loxonePath] = payload
    }

    def  storeZigbeePayload( zigbeeDeviceName,  payload) {
        zigbeePayloads[zigbeeDeviceName] = payload
    }

}

// main bridge class
@Slf4j
class Bridge implements MqttCallback {
    final mqttCache = new MqttCache()
    MqttAsyncClient mqttClient
    final jsonSlurper = new JsonSlurper()
    def configuration = new Configuration()
    private CountDownLatch m_latch
    def engine = new groovy.text.GStringTemplateEngine()
    def zigbeeRefreshTimer



    def connect() {
        int rnd = (Math.random() * 100) as int

        MqttConnectOptions mqttConnectOptions = new MqttConnectOptions();
        mqttConnectOptions.setAutomaticReconnect(true);
        mqttConnectOptions.setCleanSession(true)

        mqttClient = new MqttAsyncClient("tcp://${Env.mqttHost}:${Env.mqttPort}", "zigbee2loxone_bridge_${rnd}", new MemoryPersistence())
        mqttClient.callback = this
        mqttClient.connect(mqttConnectOptions).waitForCompletion(60000)
        mqttClient.subscribe("loxone/#", 0)
        mqttClient.subscribe("zigbee/#", 0)

        m_latch  = new CountDownLatch(1)

        if(zigbeeRefreshTimer == null) {
            zigbeeRefreshTimer = new Timer()
            zigbeeRefreshTimer.scheduleAtFixedRate(()->requestZigbeeData(), 1, Env.ZIGBEE_VALUES_REFRESH_PERIOD)
        }

        waitFinish()
    }

    void waitFinish() {
        m_latch.await()
    }

    @Override
    void connectionLost(Throwable cause) {
        m_latch.countDown()
    }

    @Override
    void messageArrived(String topic, MqttMessage mqttMessage) throws Exception {
        try {
            log.debug("message arrived at topic '{}', payload '{}'", topic, new String(mqttMessage.payload) )
            if (topic.startsWith("loxone/")) {
                processLoxoneMqttMessage(topic, mqttMessage);
            }
            if (topic.startsWith("zigbee/")) {
                processZigbeeMqttMessage(topic, mqttMessage);
            }
        } catch (Exception e) {
            log.error("Failed to process mqtt message", e)
        }
    }

    @Override
    void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {

    }

    void processLoxoneMqttMessage(String topic, MqttMessage mqttMessage) {
        def loxonePath = topic.replaceFirst("loxone/", "")
        def mapping = configuration.getMappingForLoxonePath(loxonePath)
        if(mapping != null) {
            def payload = jsonSlurper.parse(mqttMessage.payload)
            mqttCache.storeLoxonePayload(loxonePath, payload)
            mapping.l2zPayloadMappings?.each { m ->
                def template = engine.createTemplate(m.mappingFormula).make([payload: payload])
                def zigbeePayload = template.toString()
                if(zigbeePayload != "null") {
                    sendToZigbeeDevice(mapping.zigbeeDeviceName, zigbeePayload)
                }
            }
        }
    }

    void processZigbeeMqttMessage(String topic, MqttMessage mqttMessage) {
        def zigbeeDeviceName = getZigbeeDeviceName(topic)
        if (topic.endsWith(zigbeeDeviceName)) {
            forwardZigbeeToLoxone(topic, mqttMessage);
        }
    }


    def  forwardZigbeeToLoxone(String topic, MqttMessage mqttMessage) {
        def zigbeeDeviceName = getZigbeeDeviceName(topic)
        def mapping = configuration.getMappingForZigbeeDevice(zigbeeDeviceName)
        if(mapping != null) {

            def payload
            try {
                payload = jsonSlurper.parse(mqttMessage.payload)
            } catch (Exception e) {
                payload = new String(mqttMessage.payload)
            }

            mqttCache.storeZigbeePayload(zigbeeDeviceName, payload)

            // transform and send to zigbee
            mapping.z2lPayloadMappings?.each { m ->
                def template = engine.createTemplate(m.mappingFormula).make([payload: payload])
                try {
                    def loxonePayload = template.toString()
                    if(loxonePayload != "null") {
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
        def mqttTopic = "zigbee/${zigbeeDeviceName}/set"
        def mqttMessage = new MqttMessage(payload: mqttPayload)
        mqttClient.publish(mqttTopic, mqttMessage)
    }

    void sendToLoxoneComponent(String loxoneSendPath, String mqttPayload) {
        def mqttTopic = "loxone/${loxoneSendPath}"
        def mqttMessage = new MqttMessage(payload: mqttPayload)
        sendMqttMessage(mqttTopic, mqttMessage)
    }

    // for all configured zigbee device mappings, try to get actual data from devices
    void requestZigbeeData() {
        if(mqttClient.isConnected()) {
            configuration.getConfiguredZigbeeDeviceNames().each { deviceName ->
                def mqttTopic = "zigbee/${deviceName}/get"
                def mqttMessage = new MqttMessage(payload: '{"state": ""}')
                sendMqttMessage(mqttTopic, mqttMessage)
            }
        }
    }

    def sendMqttMessage(String mqttTopic, MqttMessage mqttMessage) {
        log.info("Sending mqtt message to topic '{}', payload '{}'", mqttTopic, new String(mqttMessage.payload) )
        mqttMessage.setQos(0)
        mqttClient.publish(mqttTopic, mqttMessage).waitForCompletion(5000)
    }

    void run() {
        while (true) {
            try {
                configuration.load("exampleConfiguration.json" as File)
                connect()
            } catch(Exception e) {
                log.error("Failed to start bridge, reason: {}", e.toString())
                sleep(3000)
            }
        }
    }
}

// run main loop
new Bridge().run()
