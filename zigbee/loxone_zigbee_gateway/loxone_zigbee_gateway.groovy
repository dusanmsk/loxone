@Grab(group = 'org.eclipse.paho', module = 'mqtt-client', version = '0.4.0')
@Grab(group = 'org.slf4j', module = 'slf4j-api', version = '1.6.1')
@Grab(group = 'org.slf4j', module = 'slf4j-simple', version = '1.6.1')

import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttMessage

import java.nio.charset.Charset
import java.util.concurrent.Executors

/**
 * Zigbee <--> loxone bridge
 *
 * It transforms mqtt messages between zigbee and loxone.
 *
 * Messages from zigbee to loxone are processed as following example:
 *
 * zigbee/sensor_1 { "temperature":24.93, "linkquality":47, "battery":95 }*
 * is transformed to 5 udp messages:
 *
 * zigbee/sensor_1/temperature 24.93
 * zigbee/sensor_1/linkquality 47
 * zigbee/sensor_1/battery 95
 * zigbee/sensor_1/presence 1       TODO
 * zigbee/sensor_1/presence 0       TODO
 *
 * They should be easily parsed in loxone as:
 * zigbee/sensor_1/temperature \v
 *
 * Messages from loxone to zigbee are processed by following way:
 * node-lox-mqtt-gateway (bridge subproject) connects to loxone as "web client" and see all controllers and values as connected user see in web browser.
 * It sends all value change events to mqtt topic lox_out/ROOM/CATEGORY/COMPONENT_NAME/state { value : XYZ }
 * If the loxone virtual output is in category 'zigbee_out', it is send by mqtt gateway to zigbee bridge and then by air to the zigbee device.
 * Name of virtual output is used to set name of zigbee device which will receive that message.
 *
 * For example if you want to control IKEA led bulb which is named 'led1' in zigbee2mqtt, its zigbee mqtt address and payload is:
 * zigbee/led1/set { brightness: 100 }
 *
 * So you need to create loxone virtual output in category 'zigbee_out' named:
 * led1_set_brightness
 *
 * and gateway will take care of sending brightness to ikea led bulb.
 */

@Slf4j
class LoxoneZigbeeGateway {

    def MQTT_HOST = System.getenv("MQTT_HOST")
    def MQTT_PORT = System.getenv("MQTT_PORT")

    def ZIGBEE2MQTT_TOPIC = System.getenv("ZIGBEE2MQTT_TOPIC")
    def LOXONE_ZIGBEE_CATEGORY = System.getenv("LOXONE_ZIGBEE_CATEGORY")
    def LOXONE_TO_MQTT_TOPIC = System.getenv("LOXONE_TO_MQTT_TOPIC")
    def MQTT_TO_LOXONE_TOPIC = System.getenv("MQTT_TO_LOXONE_TOPIC")


    def slurper = new JsonSlurper()
    MqttClient mqttClient
    def sendingPool = Executors.newFixedThreadPool(10)     // must send mqtt messages in parallel threads

    def setupMqtt() {
        def mqttUrl = "tcp://$MQTT_HOST:$MQTT_PORT"
        log.info("Connecting to mqtt ${mqttUrl}")
        mqttClient = new MqttClient(mqttUrl, "loxone_zigbee_gw", null)
        mqttClient.connect()
        subscribe(mqttClient, "${ZIGBEE2MQTT_TOPIC}/#")
        subscribe(mqttClient, "${LOXONE_TO_MQTT_TOPIC}/+/${LOXONE_ZIGBEE_CATEGORY}/#")
        log.info("Connected to mqtt ${mqttUrl} and ready")

        mqttClient.setCallback(new MqttCallback() {
            @Override
            void connectionLost(Throwable throwable) {}

            @Override
            void messageArrived(String topic, MqttMessage mqttMessage) {
                try {
                    processMessage(topic, new String(mqttMessage.payload))
                } catch (Exception e) {
                    log.error("MQTT message processing failed", e)
                }
            }

            @Override
            void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {}
        })

    }

    def run() {
        assert MQTT_HOST != null
        assert MQTT_PORT != null
        assert LOXONE_ZIGBEE_CATEGORY != null
        assert LOXONE_TO_MQTT_TOPIC != null
        assert MQTT_TO_LOXONE_TOPIC != null
        assert ZIGBEE2MQTT_TOPIC != null
        setupMqtt()
        loopForever()
    }

    def processMessage(String topic, String message) {
        log.info "Topic: ${topic}, message: ${message}"
        if (topic.startsWith("${ZIGBEE2MQTT_TOPIC}/")) {
            processZigbeeToLoxone(topic, message)
        } else if (topic.startsWith("${LOXONE_TO_MQTT_TOPIC}/") && topic.contains("/${LOXONE_ZIGBEE_CATEGORY}/")) {
            processLoxoneToZigbee(topic, message)
        }
    }

    def processLoxoneToZigbee(topic, message) {
        def tmp = topic.split("/")[-2].replace(LOXONE_TO_MQTT_TOPIC, "").split("_")
        def destZigbeeTopic = "${ZIGBEE2MQTT_TOPIC}/" + tmp[0..-2].join("/")
        def destValue = tmp[-1]
        def jsonObject = slurper.parseText(message)
        def value = jsonObject['value']
        def destJson = """{ "${destValue}" : "${value}" }"""
        sendMqtt(destZigbeeTopic, destJson)
    }

    def processZigbeeToLoxone(topic, message) {
        def zigbeeDeviceName = topic.split("/")[1]
        if (message.contains("{") && message.contains("}")) {
            def jsonObject = slurper.parseText(message)
            jsonObject.keySet().each { key ->
                def value = jsonObject[key].toString()
                sendToLoxone("${zigbeeDeviceName}/${key}", value)
            }
        } else {
            sendToLoxone(zigbeeDeviceName, message)
        }
    }

    def sendToLoxone(devicename, value) {
        def topic = "${MQTT_TO_LOXONE_TOPIC}/zigbee/${devicename}"
        sendMqtt(topic, value)
    }

    def sendMqtt(topic, value) {
        // must send in another thread or publish will block ...
        sendingPool.submit({
            mqttClient.publish(topic, value.getBytes(Charset.defaultCharset()), 2, false)
        })

    }

    def subscribe(MqttClient mqttClient, topic) {
        mqttClient.subscribe(topic)
        log.info("Subscribed to $topic")
    }

    void loopForever() {
        while(true) {
            Thread.sleep(Long.MAX_VALUE);
        }
    }
}


// only grab artifacts and exit
if (args.toString().contains("init")) {
    println "Grab artifacts"
    System.exit(0)
}

new LoxoneZigbeeGateway().run()

