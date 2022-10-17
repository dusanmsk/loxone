@GrabResolver(name = 'Paho', root = 'https://repo.eclipse.org/content/repositories/paho-releases/')
@Grab(group = 'org.eclipse.paho', module = 'org.eclipse.paho.client.mqttv3', version = '1.2.0')
@Grab(group = 'org.slf4j', module = 'slf4j-simple', version = '1.7.13')

import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttMessage

import java.util.concurrent.CountDownLatch

class Devel {
    static def exampleConfiguration = [
        mapping: [
                [
                    //loxoneRecvPath: 'zigbee/devel/test_zigbee_tlacitko_1/state',
                    //l2zMapping: '{ "state" : "${payload.active.toUpperCase()}" }',       // from loxone to zigbee
                    zigbeeDeviceName: 'sock_repro_obyv',
                    //loxoneSendPath: ['zigbee/devel/test_zigbee_tlacitko_1/cmd'],
                    //z2lMapping: ['${payload.state.toLowerCase()}']                         // from zigbee to loxone

                    l2zPayloadMappings: [
                            [
                                loxoneTopic: 'zb/devel/test_zigbee_tlacitko_1/state',
                                mappingFormula: '{ "state" : "${payload.active.toUpperCase()}" }'
                            ]
                    ],
                    z2lPayloadMappings: [
                            [
                                loxoneTopic: 'zb/devel/test_zigbee_tlacitko_1/cmd',
                                mappingFormula:'${payload.state.toLowerCase()}'
                            ]
                    ],
                ],

                [
                        zigbeeDeviceName: 'aquara_1',
                        z2lPayloadMappings: [
                            [
                                    loxoneTopic: 'zb/devel/test_zigbee_temperature/cmd',
                                    mappingFormula:  '${payload.temperature}'
                            ],
                            [
                                    loxoneTopic: 'zb/devel/test_zigbee_humidity/cmd',
                                    mappingFormula:  '${payload.humidity}'
                            ]
                        ]

                ],

                [
                        zigbeeDeviceName: 'bulb_1',
                        l2zPayloadMappings: [
                                [
                                        loxoneTopic: 'zb/devel/devel/state',
                                        mappingFormula: '{ "brightness" : "${payload.value}" }'
                                ]
                        ]
                ]
        ]


    ]

}

class Configuration {

    def configuration = Devel.exampleConfiguration
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
        //configuration = Devel.exampleConfiguration
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

@Slf4j
class Bridge implements MqttCallback {
    final mqttCache = new MqttCache()
    MqttAsyncClient mqttClient
    final jsonSlurper = new JsonSlurper()
    def configuration = new Configuration()
    private CountDownLatch m_latch = new CountDownLatch(1)
    def engine = new groovy.text.GStringTemplateEngine()
    def zigbeeRefreshTimer = new Timer()

    String host = "rpi4"
    long ZIGBEE_VALUES_REFRESH_PERIOD = 120000      // ako casto sa maju nacitat data zo vsetkych namapovanych zigbee devices

    def connect() {
        configuration.load("exampleConfiguration.json" as File)
        int rnd = (Math.random() * 100) as int
        mqttClient = new MqttAsyncClient("tcp://${host}:1883", "SubscriberClient${rnd}")
        mqttClient.callback = this
        //mqttClient.setTimeToWait(3000)
        mqttClient.connect().waitForCompletion(5000)
        mqttClient.subscribe("loxone/#", 0)
        mqttClient.subscribe("zigbee/#", 0)

        zigbeeRefreshTimer.scheduleAtFixedRate(()->requestZigbeeData(), 1, ZIGBEE_VALUES_REFRESH_PERIOD)
        //requestZigbeeData()

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
            //log.info("message arrived at topic '{}', payload '{}'", topic, new String(mqttMessage.payload) )
            if (topic.startsWith("loxone/")) {
                processLoxoneMqttMessage(topic, mqttMessage);
            }
            if (topic.startsWith("zigbee/")) {
                processZigbeeMqttMessage(topic, mqttMessage);
            }
        } catch (Exception e) {
            println e
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
                    log.error(e)
                }
            }
        }
    }


    String getLoxoneComponentPath(String topic) {
        // loxone/category/room/component_name/state -> category/room/component_name
        def splt = topic.split("/")
        return "${splt[1]}/${splt[2]}/${splt[3]}"
    }

    String getZigbeeDeviceName(String topic) {
        // zigbee/device_name/status -> device_name
        def splt = topic.split("/")
        return splt[1]
    }


    void sendToZigbeeDevice(String zigbeeDeviceName, String mqttPayload) {
        def mqttTopic = "zigbee/${zigbeeDeviceName}/set"
        def mqttMessage = new MqttMessage(payload: mqttPayload)
        mqttMessage.setQos(0)
        mqttClient.publish(mqttTopic, mqttMessage)
    }

    void sendToLoxoneComponent(String loxoneSendPath, String mqttPayload) {
        def mqttTopic = "loxone/${loxoneSendPath}"
        def mqttMessage = new MqttMessage(payload: mqttPayload)
        mqttMessage.setQos(0)
        sendMqttMessage(mqttTopic, mqttMessage)
    }

    void requestZigbeeData() {
        configuration.getConfiguredZigbeeDeviceNames().each {deviceName->
            def mqttTopic = "zigbee/${deviceName}/get"
            def mqttMessage = new MqttMessage(payload: '{"state": ""}')
            mqttMessage.setQos(0)
            sendMqttMessage(mqttTopic, mqttMessage)
        }
    }

    def sendMqttMessage(String mqttTopic, MqttMessage mqttMessage) {
        log.info("Sending mqtt message to topic '{}', payload '{}'", mqttTopic, new String(mqttMessage.payload) )
        mqttClient.publish(mqttTopic, mqttMessage)
    }

}

def bridge = new Bridge()
bridge.connect()
