@Grab(group = 'org.eclipse.paho', module = 'mqtt-client', version = '0.4.0')
@Grab(group = 'org.slf4j', module = 'slf4j-api', version = '1.6.1')
@Grab(group = 'org.slf4j', module = 'slf4j-simple', version = '1.6.1')

import groovy.util.logging.Slf4j
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttMessage

/**
 * mqtt -> loxone udp bridge
 *
 * Sends messages from mqtt to loxone as udp messages.
 *
 * Topic that will be send to loxone must be defined in MQTT_TO_LOXONE_TOPIC environment variable, comma separated.
 *
 * For example:
 * MQTT_TO_LOXONE_TOPIC="lox_in"
 *
 * UDP packet content is constructed as concatenation of mqtt topic and payload data, for example
 *
 * topic:   lox_in/zigbee/room1/temperature
 * payload: 22.5
 *
 * is sent as udp packet containing string
 * "zigbee/room1/temperature 22.5"
 *
 *
 * Environment variables:
 * MQTT_TO_LOXONE_TOPIC
 * MQTT_HOST
 * MQTT_PORT
 * LOXONE_ADDRESS
 * LOXONE_UDP_PORT
 *
 */

@Slf4j
class MqttToLoxoneUDP {

    def RECONNECT_TIME_SEC = 30

    def LOXONE_ADDRESS = System.getenv("LOXONE_ADDRESS")
    def LOXONE_UDP_PORT = System.getenv("LOXONE_UDP_PORT") as Integer
    def MQTT_HOST = System.getenv("MQTT_HOST")
    def MQTT_PORT = System.getenv("MQTT_PORT")
    def MQTT_TO_LOXONE_TOPIC = System.getenv("MQTT_TO_LOXONE_TOPIC")

    DatagramSocket sendUdpSocket
    MqttClient mqttClient

    def setupMqtt() {
        def mqttUrl = "tcp://$MQTT_HOST:$MQTT_PORT"
        log.info("Connecting to mqtt ${mqttUrl}")
        mqttClient = new MqttClient(mqttUrl, "mqtt2loxoneudp", null)
        mqttClient.connect()
        mqttClient.subscribe("${MQTT_TO_LOXONE_TOPIC}/#")
        log.info("Connected to mqtt ${mqttUrl} and ready")

        sendUdpSocket = new DatagramSocket()

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

        assert LOXONE_ADDRESS != null
        assert LOXONE_UDP_PORT != null
        assert MQTT_HOST != null
        assert MQTT_PORT != null
        assert MQTT_TO_LOXONE_TOPIC != null

        while (true) {
            try {
                setupMqtt()
            } catch (Exception e) {
                log.error("Exception caught", e)
            } finally {
                log.info("Sleeping for ${RECONNECT_TIME_SEC} seconds ...")
                Thread.sleep(1000 * RECONNECT_TIME_SEC)
            }
        }
    }

    def processMessage(topic, message) {
        topic = topic.replace(MQTT_TO_LOXONE_TOPIC, "").replaceAll("//", "/").replaceFirst("/", "")
        sendToLoxoneUDP("${topic} ${message}")
    }

    def sendToLoxoneUDP(String msg) {
        log.info("""Sending UDP "${msg}" to $LOXONE_ADDRESS:$LOXONE_UDP_PORT""")
        def packet = new DatagramPacket(msg.getBytes(), msg.getBytes().length, InetAddress.getByName(LOXONE_ADDRESS), LOXONE_UDP_PORT)
        sendUdpSocket.send(packet)
    }
}


// only grab artifacts and exit
if (args.toString().contains("init")) {
    println "Grab artifacts"
    System.exit(0)
}

new MqttToLoxoneUDP().run()

