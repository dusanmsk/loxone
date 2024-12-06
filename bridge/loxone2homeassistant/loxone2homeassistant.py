import json
import sys
import traceback
import paho.mqtt.client as mqtt
import logging
import os
import requests
import helpers
import time, threading

from helpers import get_env_var, miniserver_configuration

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
log = logging.getLogger("loxone2homeassistant")
log.setLevel(helpers.getLogLevel('LOXONE2HOMEASSISTANT_LOGLEVEL'))

mqtt_host = get_env_var('MQTT_HOST')
mqtt_port = int(get_env_var('MQTT_PORT'))
loxone_mqtt_topic_name = get_env_var('LOXONE_MQTT_TOPIC_NAME')
loxone_host = get_env_var('LOXONE_ADDRESS')  # TODO LOXONE_HOST
loxone_username = get_env_var('LOXONE_USERNAME')
loxone_password = get_env_var('LOXONE_PASSWORD')
miniserver_configuration_topic = f"{loxone_mqtt_topic_name}/configuration"

# list of topics that were already processed and its configuration has been sent do haas 
haas_already_configured_topics = []

mqtt_client = None
config_requesting_thread = None
haas_helper = helpers.HomeAssistantConfigurationHelper()

def mqtt_on_connect(client, userdata, flags, rc, properties):
    if rc == 0:
        log.info("Connected to MQTT broker")
        topics = [
            f"{loxone_mqtt_topic_name}/by-uuid/#",
            f"{miniserver_configuration_topic}"
        ]
        for t in topics:
            log.info(f"Subscribing to {t}")
            client.subscribe(t)
        requestMiniserverConfiguration()
    else:
        log.error("Failed to connect to MQTT broker")
        sys.exit(1)

def sendHaasConfiguration(haas_topic, haas_payload):
    json_payload = json.dumps(haas_payload, indent=True)
    log.debug(f"Publishing to {haas_topic} payload:\n{json_payload}")
    mqtt_client.publish(haas_topic, '')  # delete previous config
    mqtt_client.publish(haas_topic, json_payload)  # send new config

def sendMqttConfigurationToHaas(payload_json, control):
    controlType = control['type'].lower()
    if controlType == 'switch':
        cfg = haas_helper.createHaasSwitchConfiguration(control)
    elif controlType == 'infoonlyanalog':
        cfg = haas_helper.createHaasAnalogSensorConfiguration(control)
    # elif controlType == 'infoonlydigital':
    #     log.warning("TODO: infoonlydigital")
    #     return
    # elif controlType == 'meter':
    #     log.warning("TODO: meter")
    #     return
    elif controlType == 'timedswitch':
        cfg = haas_helper.createHaasTimedSwitchConfiguration(control)
    elif controlType == 'slider':
        cfg = haas_helper.createHaasSliderConfiguration(control)
    elif controlType == 'pushbutton':
        cfg = haas_helper.createHaasPushbuttonConfiguration(control)
    elif controlType == 'jalousie':
        cfg = haas_helper.createHaasJalousieConfiguration(control)
    # elif controlType == 'lightcontroller':
    #     log.warning("TODO: lightcontroller")
    #     return
    elif controlType == 'radio':
        cfg = haas_helper.createHaasRadio8ComponentConfiguration(control)
    else:
        log.warning(f"Unknown control type {controlType}")
        return
    if cfg:
        if isinstance(cfg, list):
            for (topic, payload) in cfg:
                sendHaasConfiguration(topic, payload)
        else:
            sendHaasConfiguration(cfg[0], cfg[1])


# skips all not named controls. Only for debugging purposes
debug_allowed_controls = ['homeassistant']


def skipDebugging(metadata):
    for i in debug_allowed_controls:
        if 'room' in metadata and i in metadata['room']:
            return False
    return True


def mqtt_on_message(client, userdata, msg):
    try:
        topic = msg.topic
        if topic == miniserver_configuration_topic:
            processMiniserverConfigurationResponse(msg.payload.decode())
            return
        if topic.endswith('/state'):
            controlUuid = topic.split('/')[2]
            payload_json = json.loads(msg.payload.decode())
            metadata = payload_json['metadata']
            control = miniserver_configuration.getControlByUuid(controlUuid)
            if control:
                stateTopic = topic
                if not stateTopic in haas_already_configured_topics:
                    if skipDebugging(metadata):
                        return
                    log.debug(f"Generating haas configuration for {topic}, payload: {payload_json}")
                    sendMqttConfigurationToHaas(payload_json, control)
                    haas_already_configured_topics.append(stateTopic)
            return
    except Exception as e:
        log.error(f"Error: {e}")
        traceback.print_exc()


def mqtt_on_disconnect(a, b, c, rc, e):
    log.error(f"Disconnected from MQTT broker with code {rc}")


def print_progress():
    global processed_cnt, err_cnt
    log.info(f"Processed {processed_cnt} messages, errors: {err_cnt}")


def connectToMQTTAndLoopForever():
    global mqtt_client
    log.info(f"Connecting to mqtt at {mqtt_host}:{mqtt_port}")
    mqtt_client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
    mqtt_client.reconnect_delay_set(min_delay=1, max_delay=120)
    mqtt_client.on_connect = mqtt_on_connect
    mqtt_client.on_message = mqtt_on_message
    mqtt_client.on_disconnect = mqtt_on_disconnect
    if (helpers.getLogLevel('LOXONE2HOMEASSISTANT_LOGLEVEL') <= logging.INFO):
        mqtt_client.enable_logger()
    mqtt_client.connect(mqtt_host, mqtt_port)
    log.info("Starting MQTT loop")
    mqtt_client.loop_forever(retry_first_connection=True)


def requestMiniserverConfiguration():
    global mqtt_client
    if mqtt_client:
        log.debug("Requesting loxone configuration from mqtt")
        mqtt_client.publish(f"{miniserver_configuration_topic}/get", "")

def processMiniserverConfigurationResponse(payload):
    helpers.miniserver_configuration.parse(payload)

# periodicaly request loxone configuration
def startMiniserverConfigurationReqestingThread():
    global config_requesting_thread
    def requestMiniserverConfigurationWorker():
        while True:
            requestMiniserverConfiguration()
            # ask for new configuration every 5 minutes. If configuration is not ready yet, ask every 5 seconds
            time.sleep(300 if miniserver_configuration.was_loaded() else 5)
    config_requesting_thread = threading.Thread(target=requestMiniserverConfigurationWorker)
    config_requesting_thread.start()
    log.debug("Configuration requesting thread started")

def main():
    startMiniserverConfigurationReqestingThread()
    connectToMQTTAndLoopForever()

if __name__ == '__main__':
    main()
