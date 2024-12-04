import json
import sys
import traceback
from json import JSONDecodeError
from unidecode import unidecode
import paho.mqtt.client as mqtt
import logging
import os

import requests


def get_env_var(name):
    value = os.environ.get(name)
    assert value, f"{name} environment variable is not set."
    return value


def getLogLevel(env_variable_name):
    level = str(os.getenv(env_variable_name, 'info')).lower()
    if level == 'debug':
        return logging.DEBUG
    elif level == 'info':
        return logging.INFO
    elif level == 'warning':
        return logging.WARNING
    elif level == 'error':
        return logging.ERROR
    elif level == 'critical':
        return logging.CRITICAL
    else:
        return logging.INFO


logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
log = logging.getLogger("loxone2homeassistant")
log.setLevel(getLogLevel('LOXONE2HOMEASSISTANT_LOGLEVEL'))

mqtt_host = get_env_var('MQTT_HOST')
mqtt_port = int(get_env_var('MQTT_PORT'))
loxone_mqtt_topic_name = get_env_var('LOXONE_MQTT_TOPIC_NAME')
loxone_host = get_env_var('LOXONE_ADDRESS')  # TODO LOXONE_HOST
loxone_username = get_env_var('LOXONE_USERNAME')
loxone_password = get_env_var('LOXONE_PASSWORD')

loxone_configuration = None
loxone_rooms_by_uuid = {}
loxone_rooms_by_name = {}  # name is unified
loxone_categories_by_uuid = {}
loxone_categories_by_name = {}  # name is unified
loxone_controls_by_uuid = {}
loxone_controls_by_name = {}  # name is unified

# list of topics that were already processed and its configuration has been sent do haas 
haas_already_configured_topics = []

mqtt_client = None


def mqtt_on_connect(client, userdata, flags, rc, properties):
    if rc == 0:
        log.info("Connected to MQTT broker")
        topic = f"{loxone_mqtt_topic_name}/raw/#"
        log.info(f"Subscribing to {topic}")
        client.subscribe(topic)
    else:
        log.error("Failed to connect to MQTT broker")
        sys.exit(1)


def extractTopic(topic):
    splt = topic.split('/')
    return splt[1], splt[2], splt[3]


dbg_types = []


# fix mqtt topic name, remove duplicate and ending slashes etc...
def toMqttTopicName(topic):
    topic = topic.replace("//", '/').replace("+", "_").replace("#", "_")
    topic = normalize_name(topic)
    if topic.endswith('/'):
        topic = topic[:-1]
    return topic


def sendHaasConfiguration(haas_topic, haas_payload):
    json_payload = json.dumps(haas_payload, indent=True)
    log.debug(f"Publishing to {haas_topic} payload:\n{json_payload}")
    mqtt_client.publish(haas_topic, '')  # delete previous config
    mqtt_client.publish(haas_topic, json_payload)  # send new config


def getLoxoneHaasUuid(metadata):
    return normalize_name(f"loxone_{metadata['name']}-{metadata['uuidAction']}")


def getLoxoneHaasComponentName(metadata):
    return normalize_name(f"loxone-{metadata['name']}-{metadata['uuidAction']}")  # todo preco v metadatach nemam categoryName a roomName?


def getDevicePayload(metadata, identifiers):
    return {
        "name": "Loxone",
        "identifiers": identifiers
    }


def addCommonAttributes(haas_payload, metadata, identifiers=None):
    haas_payload['unique_id'] = metadata['stateTopic'].replace('/', '_')
    haas_payload["device"] = getDevicePayload(metadata, identifiers)
    haas_payload["name"] = getLoxoneHaasComponentName(metadata)
    haas_payload["enabled_by_default"] = "true"


def createHaasSliderConfiguration(metadata):
    haas_uuid = getLoxoneHaasUuid(metadata)
    haas_topic = toMqttTopicName(f"homeassistant/number/{haas_uuid}/config")
    control = loxone_controls_by_uuid[metadata['uuidAction']]
    haas_payload = {
        "command_topic": metadata['commandTopic'],
        "state_topic": metadata['stateTopic'],
        "min": control['details']['min'],
        "max": control['details']['max'],
        "step": control['details']['step'],
        "mode": "slider",
        "value_template": "{{ value_json.value }}",
        #"state_value_template": "{{ value_json.value }}",
        "command_template": "{{ value }}",
        "qos": 0,
        "retain": "false",
    }
    addCommonAttributes(haas_payload, metadata, ['slider'])
    return (haas_topic, haas_payload)

def createHaasSwitchConfiguration(metadata):
    haas_uuid = getLoxoneHaasUuid(metadata)
    haas_topic = toMqttTopicName(f"homeassistant/switch/{haas_uuid}/config")
    # TODO nesahat !!! takto funguje s gen1 !! gen2 upravit tak aby to sedelo na toto
    haas_payload = {
        "command_topic": metadata['commandTopic'],
        "state_topic": metadata['stateTopic'],
        "value_template": "{{ value_json.active }}",
        "state_value_template": "{{ value_json.active }}",
        "payload_on": 'on',
        "payload_off": 'off',
        "state_on": 'on',
        "state_off": 'off',
        "qos": 0,
        "retain": "false",
    }
    addCommonAttributes(haas_payload, metadata, ['switch'])
    return (haas_topic, haas_payload)


def createHaasAnalogSensorConfiguration(metadata):
    haas_uuid = getLoxoneHaasUuid(metadata)
    haas_topic = toMqttTopicName(f"homeassistant/sensor/{haas_uuid}/config")
    haas_payload = {
        # "unique_id": metadata['stateTopic'].replace('/', '_'),
        # "device": getDevicePayload(metadata, ['analog_sensor']),
        # "name": getLoxoneHaasComponentName(metadata),
        "state_topic": metadata['stateTopic'],
        "value_template": "{{ value_json.value }}"
    }
    addCommonAttributes(haas_payload, metadata, ['analog_sensor'])
    return (haas_topic, haas_payload)


def sendMqttConfigurationToHaas(metadata):
    topic = None
    payload = None
    controlType = metadata['type'].lower()
    if controlType == 'switch':
        (topic, payload) = createHaasSwitchConfiguration(metadata)
        pass
    elif controlType == 'infoonlyanalog':
        # todo docasne blokujem aby mi neplevelilo dashboardy
        #(topic, payload) = createHaasAnalogSensorConfiguration(metadata)
        pass
    elif controlType == 'infoonlydigital':
        pass
    elif controlType == 'meter':
        pass
    elif controlType == 'timedswitch':
        pass
    elif controlType == 'slider':
        (topic, payload) = createHaasSliderConfiguration(metadata)
    else:
        log.warning(f"Unknown control type {controlType}")
    if(topic and payload):
        sendHaasConfiguration(topic, payload)


# skips all not named controls. Only for debugging purposes
debug_allowed_controls = ['R2 generator']
def skipDebugging(metadata):
    for i in debug_allowed_controls:
        if i in str(metadata['name']):
            return False
    return True

def mqtt_on_message(client, userdata, msg):
    try:
        topic = msg.topic
        if topic.endswith('/state'):
            payload = json.loads(msg.payload.decode())
            metadata = payload['metadata']
            stateTopic = metadata['stateTopic']
            if not stateTopic in haas_already_configured_topics:
                if skipDebugging(metadata):
                    return
                log.debug(f"Generating haas configuration for {topic}, payload: {payload}")
                sendMqttConfigurationToHaas(metadata)
                haas_already_configured_topics.append(stateTopic)
    except Exception as e:
        log.error(f"Error: {e}")
        traceback.print_exc()


def normalize_name(name):
    return unidecode(str(name)).replace(' ', '_').lower()


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
    if (getLogLevel('LOXONE2HOMEASSISTANT_LOGLEVEL') <= logging.INFO):
        mqtt_client.enable_logger()
    mqtt_client.connect(mqtt_host, mqtt_port)
    log.info("Starting MQTT loop")
    mqtt_client.loop_forever(retry_first_connection=True)


def extractControl(uuid, control):
    loxone_controls_by_uuid[uuid] = control
    loxone_controls_by_name[normalize_name(control['name'])] = control


def getLoxoneConfiguration():
    global loxone_configuration, loxone_rooms_by_uuid, loxone_rooms_by_name, loxone_categories_by_uuid, loxone_categories_by_name
    response = requests.get(url=f"http://{loxone_host}/data/LoxAPP3.json", auth=(loxone_username, loxone_password))
    if response.status_code != 200:
        raise Exception("Failed to get loxone configuration")
    loxone_configuration = json.loads(response.text)
    print(json.dumps(loxone_configuration, indent=True))
    for uuid in loxone_configuration['rooms']:
        room = loxone_configuration['rooms'][uuid]
        loxone_rooms_by_uuid[uuid] = room
        loxone_rooms_by_name[normalize_name(room['name'])] = room
    for uuid in loxone_configuration['cats']:
        category = loxone_configuration['cats'][uuid]
        loxone_categories_by_uuid[uuid] = category
        loxone_categories_by_name[normalize_name(category['name'])] = category
    for uuid in loxone_configuration['controls']:
        control = loxone_configuration['controls'][uuid]
        if 'subControls' in control:
            for uuid in control['subControls']:
                subControl = control['subControls'][uuid]
                extractControl(uuid, subControl)
        else:
            extractControl(uuid, control)
    pass


def main():
    getLoxoneConfiguration()
    connectToMQTTAndLoopForever()


if __name__ == '__main__':
    main()
