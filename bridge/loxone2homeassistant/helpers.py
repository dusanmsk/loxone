import json
import logging
import os
import re
from unidecode import unidecode


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


loxone_mqtt_topic_name = get_env_var('LOXONE_MQTT_TOPIC_NAME')
log = logging.getLogger(__name__)
log.setLevel(getLogLevel('LOXONE2HOMEASSISTANT_LOGLEVEL'))


class MiniserverConfiguration:

    def __init__(self):
        self.loxone_rooms_by_uuid = {}
        self.loxone_rooms_by_name = {}
        self.loxone_categories_by_uuid = {}
        self.loxone_categories_by_name = {}
        self.loxone_controls_by_uuid = {}
        self.loxone_controls_by_name = {}
        self._loaded = False

    def was_loaded(self):
        return self._loaded

    def extractControl(self, uuid, control):
        self.loxone_controls_by_uuid[uuid] = control
        self.loxone_controls_by_name[control['name']] = control  # todo asi sa nepouziva

    def parse(self, payload):
        loxone_configuration = json.loads(payload)
        for uuid in loxone_configuration['rooms']:
            room = loxone_configuration['rooms'][uuid]
            self.loxone_rooms_by_uuid[uuid] = room
            self.loxone_rooms_by_name[room['name']] = room
        for uuid in loxone_configuration['cats']:
            category = loxone_configuration['cats'][uuid]
            self.loxone_categories_by_uuid[uuid] = category
            self.loxone_categories_by_name[category['name']] = category
        for uuid in loxone_configuration['controls']:
            control = loxone_configuration['controls'][uuid]
            if 'subControls' in control:
                for uuid in control['subControls']:
                    subControl = control['subControls'][uuid]
                    self.extractControl(uuid, subControl)
            else:
                self.extractControl(uuid, control)
        self._loaded = True
        log.debug("Loxone miniserver configuration processed")

    def getControlByUuid(self, controlUuid):
        return self.loxone_controls_by_uuid.get(controlUuid)

    def getRoomNameByUuid(self, roomUuid):
        return self.loxone_rooms_by_uuid[roomUuid]['name']


class HomeAssistantConfigurationHelper:

    # note - hotovo funkcne
    def createHaasSwitchConfiguration(self, control):
        uuid = control['uuidAction']
        haas_uuid = f"{self.getLoxoneHaasUuid(control)}"
        haas_topic = toMqttTopicName(f"homeassistant/switch/{haas_uuid}/config")
        command_topic = toCommandTopic(uuid)
        state_topic = toStateTopic(uuid)
        control_name = control['name']
        room = miniserver_configuration.getRoomNameByUuid(control['room'])
        haasName = self.getLoxoneHaasComponentName(control_name, room)
        haas_payload = {
            "command_topic": command_topic,
            "state_topic": state_topic,
            "value_template": "{{ value_json.state.active }}",
            "state_value_template": "{{ value_json.state.active }}",
            "payload_on": 'on',
            "payload_off": 'off',
            "state_on": 'on',
            "state_off": 'off',
            "qos": 0,
            "retain": "false",
            "unique_id": haas_uuid,
            # TODO device "device": helpers.getDevicePayload(metadata, ['switch']),
            "name": haasName,
            "enabled_by_default": "true"
        }
        return (haas_topic, haas_payload)

    # note - hotovo funkcne
    def createHaasRadio8ComponentConfiguration(self, control):
        # structure of state:
        # "state": {
        #     "value": x,              # x = 1-8
        #     "text": "xxxxx"          # text representation of the value
        # },
        #
        # commands are: 1,2,3,4,5,6,7,8,reset
        prepared_configuration = []
        uuid = control['uuidAction']
        command_topic = toCommandTopic(uuid)
        state_topic = toStateTopic(uuid)
        prepared_configuration.append(self.createRadioButtonButtonConfiguration(control, 0, command_topic))
        for i in range(1, 9):
            prepared_configuration.append(self.createRadioButtonButtonConfiguration(control, i, command_topic))
        prepared_configuration.append(self.createGenericSensorConfiguration(control, 'value', '{{ value_json.state.value }}', state_topic))
        prepared_configuration.append(self.createGenericSensorConfiguration(control, 'text', '{{ value_json.state.text }}', state_topic))
        return prepared_configuration

    # note - hotovo funkcne
    def createHaasPushbuttonConfiguration(self, control):
        uuid = control['uuidAction']
        haas_uuid = f"{self.getLoxoneHaasUuid(control)}"
        haas_topic = toMqttTopicName(f"homeassistant/button/{haas_uuid}/config")
        command_topic = toCommandTopic(uuid)
        # state_topic = toStateTopic(uuid)
        control_name = control['name']
        room = miniserver_configuration.getRoomNameByUuid(control['room'])
        haasName = self.getLoxoneHaasComponentName(control_name, room)
        haas_payload = {
            "value_template": "{{ value }}",
            "unique_id": haas_uuid,
            # todo "device": helpers.getDevicePayload(metadata, ['button']),
            "name": haasName,
            "command_topic": command_topic,
            "payload_press": "pulse",
            "enabled_by_default": "true"
        }
        return (haas_topic, haas_payload)

    # hotovo, funguje
    def createHaasSliderConfiguration(self, control):
        uuid = control['uuidAction']
        haas_uuid = f"{self.getLoxoneHaasUuid(control)}"
        haas_topic = toMqttTopicName(f"homeassistant/number/{haas_uuid}/config")
        command_topic = toCommandTopic(uuid)
        state_topic = toStateTopic(uuid)
        control_name = control['name']
        room = miniserver_configuration.getRoomNameByUuid(control['room'])
        haasName = self.getLoxoneHaasComponentName(control_name, room)
        haas_payload = {
            "command_topic": command_topic,
            "state_topic": state_topic,
            "min": control['details']['min'],
            "max": control['details']['max'],
            "step": control['details']['step'],
            "mode": "slider",
            "value_template": "{{ value_json.state.value }}",
            # "state_value_template": "{{ value_json.value }}",
            "command_template": "{{ value }}",
            "qos": 0,
            "retain": "false",
            "unique_id": haas_uuid,
            # todo "device": helpers.getDevicePayload(metadata, ['slider']),
            "name": haasName,
            "enabled_by_default": "true"

        }
        return (haas_topic, haas_payload)

    # hotovo
    def createHaasTimedSwitchConfiguration(self, control):
        # "state": {
        #     "deactivationDelay": 0,       # -1 = trvalo zapnute, inak odpocitava od X do 0, 0 je vypnute
        #     "deactivationDelayTotal": 180
        #   },
        # commands: on, off, pulse
        state_topic = toStateTopic(control['uuidAction'])
        prepared_configuration = []
        prepared_configuration.append(self.createButtonConfiguration(control, 'On', 'on'))
        prepared_configuration.append(self.createButtonConfiguration(control, 'Off', 'off'))
        prepared_configuration.append(self.createButtonConfiguration(control, 'Start', 'pulse'))
        prepared_configuration.append(self.createGenericSensorConfiguration(control, 'Time remaining',
                                                                            '{{ value_json.state.deactivationDelay | round(0) }}',
                                                                            state_topic))
        prepared_configuration.append(self.createGenericSensorConfiguration(control, 'Status',
                                                                            '{{ "Off" if value_json.state.deactivationDelay == 0 else "On" }}',
                                                                            state_topic))

        return prepared_configuration

    # hotovo
    def createHaasAnalogSensorConfiguration(self, control):
        uuid = control['uuidAction']
        haas_uuid = f"{self.getLoxoneHaasUuid(control)}"
        haas_topic = toMqttTopicName(f"homeassistant/sensor/{haas_uuid}/config")
        state_topic = toStateTopic(uuid)
        control_name = control['name']
        room = miniserver_configuration.getRoomNameByUuid(control['room'])
        haas_name = self.getLoxoneHaasComponentName(control_name, room)
        haas_payload = {
            "state_topic": state_topic,
            "value_template": "{{ value_json.state.value }}",
            "unique_id": haas_uuid,
            # TODO "device": helpers.getDevicePayload(metadata, ['analog_sensor']),
            "name": haas_name,
            "enabled_by_default": "true"
        }
        return (haas_topic, haas_payload)

    # ok
    def createHaasJalousieConfiguration(self, control):
        # structure of state:
        # "state": {
        #     "up": 1,              # 1 when going up
        #     "down": 0,            # 1 when going down
        #     "position": 0.40442,  # *100 means position in %
        #     "shadePosition": 0,   # *100 means shade position in %
        #     "safetyActive": 0,
        #     "autoAllowed": 1,     # 0/1 allowed autopilot
        #     "autoActive": 0,      # 0/1 autopilot active
        #     "locked": 0
        # },
        #
        # commands are: FullUp, FullDown, (down, downOff), (up, upOff), shade, auto
        prepared_configuration = []
        state_topic = toStateTopic(control['uuidAction'])
        prepared_configuration.append(self.createButtonConfiguration(control, 'Full Up', 'FullUp'))
        prepared_configuration.append(self.createButtonConfiguration(control, 'Full Down', 'FullDown'))
        prepared_configuration.append(self.createButtonConfiguration(control, 'Shade', 'shade'))
        prepared_configuration.append(self.createButtonConfiguration(control, 'Enable autopilot', 'auto'))
        prepared_configuration.append(self.createButtonConfiguration(control, 'Disable autopilot', 'NoAuto'))
        prepared_configuration.append(self.createJalousieUpDownSensor(control))
        prepared_configuration.append(self.createGenericSensorConfiguration(control, 'position', '{{ (value_json.state.position * 100) | round(0) }}', state_topic))
        prepared_configuration.append(self.createGenericSensorConfiguration(control, 'shade', '{{ (value_json.state.shadePosition * 100) | round(0) }}', state_topic))
        prepared_configuration.append(self.createGenericSensorConfiguration(control, 'auto enabled', "{{ 'yes' if value_json.state.autoActive else 'no' }}", state_topic))
        return prepared_configuration

    # ok
    def createButtonConfiguration(self, control, button_name, payload_press):
        uuid = control['uuidAction']
        haas_uuid = f"{self.getLoxoneHaasUuid(control)}_{normalize_name(button_name)}"
        haas_topic = toMqttTopicName(f"homeassistant/button/{haas_uuid}/config")
        command_topic = toCommandTopic(uuid)
        componentName = control['name']
        room = miniserver_configuration.getRoomNameByUuid(control['room'])
        haas_name = self.getLoxoneHaasComponentName(componentName, room, button_name)
        haas_payload = {
            "unique_id": haas_uuid,
            # TODO "device": getDevicePayload(metadata, ['button']),
            "name": haas_name,
            "command_topic": command_topic,
            "payload_press": payload_press,
            "enabled_by_default": "true"
        }
        return haas_topic, haas_payload

    # ok
    def createRadioButtonButtonConfiguration(self, control, index, commandTopic):
        haas_uuid = f"{self.getLoxoneHaasUuid(control)}_{index}"
        haas_topic = toMqttTopicName(f"homeassistant/button/{haas_uuid}/config")
        payload = 'reset' if index == 0 else index
        control_name = control['name']
        room = miniserver_configuration.getRoomNameByUuid(control['room'])
        sub_name = control['details']['outputs'][str(index)] if index else control['details']['allOff']
        haas_name = self.getLoxoneHaasComponentName(control_name, room, sub_name)
        haas_payload = {
            # "value_template": payload,        # todo potrebujem to tu?
            "unique_id": haas_uuid,
            # TODO "device": getDevicePayload(metadata, ['button']),
            "name": haas_name,
            "command_topic": commandTopic,
            "payload_press": payload,
            "enabled_by_default": "true"  # todo false ?
        }
        return haas_topic, haas_payload

    # ok
    def createGenericSensorConfiguration(self, control, subName, value_template, state_topic):
        name_suffix = normalize_name(subName)
        haas_uuid = f"{self.getLoxoneHaasUuid(control)}_{name_suffix}"
        haas_topic = toMqttTopicName(f"homeassistant/sensor/{haas_uuid}/config")
        room = miniserver_configuration.getRoomNameByUuid(control['room'])
        haas_payload = {
            "state_topic": state_topic,
            "value_template": value_template,
            "unique_id": haas_uuid,
            # TODO device "device": getDevicePayload(metadata, ['jalouise']),
            "name": self.getLoxoneHaasComponentName(control['name'], room, subName),
            "enabled_by_default": "true"
        }
        return (haas_topic, haas_payload)

    # ok
    def createJalousieUpDownSensor(self, control):
        name_suffix = 'direction'
        haas_uuid = f"{self.getLoxoneHaasUuid(control)}_{name_suffix}"
        haas_topic = toMqttTopicName(f"homeassistant/sensor/{haas_uuid}/config")
        state_topic = toStateTopic(control['uuidAction'])
        control_name = control['name']
        room = miniserver_configuration.getRoomNameByUuid(control['room'])
        haas_name = self.getLoxoneHaasComponentName(control_name, room, name_suffix)
        # haas_uuid = f"{self.getLoxoneHaasUuid(metadata)}_up_down"
        # haas_topic = toMqttTopicName(f"homeassistant/sensor/{haas_uuid}/config")
        haas_payload = {
            "state_topic": state_topic,
            "value_template": "{{ 'up' if value_json.state.up else 'down' if value_json.state.down else '-' }}",
            "unique_id": haas_uuid,
            # todo "device": getDevicePayload(metadata, ['jalouise']),
            "name": haas_name,
            "enabled_by_default": "true"
        }
        return (haas_topic, haas_payload)

    def getLoxoneHaasUuid(self, control):
        return normalize_name(f"loxone_{control['uuidAction']}")

    def getLoxoneHaasComponentName(self, name, room, subControlName=None):
        if subControlName:
            return f"{name} - {subControlName} ({room})"
        else:
            return f"{name} ({room})"


# fix mqtt topic name, remove duplicate and ending slashes etc...
def toMqttTopicName(topic):
    topic = topic.replace("//", '/').replace("+", "_").replace("#", "_")
    if topic.endswith('/'):
        topic = topic[:-1]
    return topic


def normalize_name(name):
    return re.sub(r'\W+', '_', unidecode(str(name)).lower())


def getDevicePayload(metadata, identifiers):
    return {
        "name": "Loxone",
        "identifiers": identifiers
    }


def toCommandTopic(uuid):
    return f"{loxone_mqtt_topic_name}/by-uuid/{uuid}/cmd"


def toStateTopic(uuid):
    return f"{loxone_mqtt_topic_name}/by-uuid/{uuid}/state"


# global loxone miniserver configuration
miniserver_configuration = MiniserverConfiguration()
