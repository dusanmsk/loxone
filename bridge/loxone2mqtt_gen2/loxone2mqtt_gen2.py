import asyncio
import json
import logging
import sys
from aiomqtt import Client, MqttError
from pyloxone_api import LoxAPI

#logging.basicConfig(level=logging.DEBUG)
log = logging.getLogger("loxone2mqtt_gen2")
log.setLevel(logging.DEBUG)
log.addHandler(logging.StreamHandler())
# If you want to see what is going on at the websocket level, uncomment the following
# lines

log2 = logging.getLogger("websockets")
log2.setLevel(logging.DEBUG)
log2.addHandler(logging.StreamHandler())

loxone_api = None

# todo env
loxone_mqtt_topic_name = 'lox'

async def process_loxone_message(msg_dict):
    global loxone_api
    async with Client("localhost") as mqtt_client:
        try:
            mqtt_payload = {
                "state": {},
                "metadata": {
                    "uuidAction": None,
                    "name": None,
                    "type": None,
                    "roomUuid": None,
                    "room": None,
                    "categoryUuid": None,
                    "category": None,
                    "stateTopic": None,
                    "commandTopic": None
                }
            }
            log.debug(f"Processing loxone message: {msg_dict}")
            for state_uuid in msg_dict.keys():
                state = loxone_states_by_uuid.get(state_uuid, None)
                control = loxone_controls_by_uuid.get(state_uuid, None)
                value = msg_dict[state_uuid]
                if state:
                    state_descriptor = loxone_states_by_uuid[state_uuid]
                    state_name = state_descriptor[0]
                    control_uuid = state_descriptor[1]
                    control = loxone_controls_by_uuid[control_uuid]
                    mqtt_uid = control['uuidAction']
                    mqtt_payload['metadata']['uuidAction'] = control['uuidAction']
                    mqtt_payload['metadata']['name'] = control['name']
                    mqtt_payload['metadata']['type'] = control['type']
                    mqtt_payload['metadata']['roomUuid'] = control['room']
                    mqtt_payload['metadata']['room'] = loxone_rooms_by_uuid[control['room']]['name']
                    mqtt_payload['metadata']['categoryUuid'] = control['cat']
                    mqtt_payload['metadata']['category'] = loxone_categories_by_uuid[control['cat']]['name']
                    mqtt_payload['metadata']['stateTopic'] = f"{loxone_mqtt_topic_name}/raw/{mqtt_uid}/state"
                    mqtt_payload['metadata']['commandTopic'] = f"{loxone_mqtt_topic_name}/raw/{mqtt_uid}/cmd"
                    mqtt_payload['state'][state_name] = value
                elif control:
                    log.warning("MSK - Not implemented yet")
                    # sem slider
                    pass
            if mqtt_payload.keys():
                topic = f"{loxone_mqtt_topic_name}/raw/{mqtt_uid}/state"
                mqtt_payload_json = json.dumps(mqtt_payload)
                # todo debounce by message hash (mapu topic -> hash) a porovnavat
                mqtt_payload_hash = hash(mqtt_payload_json)
                log.debug(f"MQTT sending to topic '{topic}' message '{mqtt_payload_json}")
                await mqtt_client.publish(topic=topic, payload=mqtt_payload_json, qos=1)
        except Exception as e:
            logging.error("Failed to process loxone control", exc_info=e)

async def background_task():
    while True:
        await asyncio.sleep(5)
        # nastav slider na 99
        await loxone_api.send_websocket_command("1df454b8-0185-e1d1-ffffba4d5d46ebcc", 99)


# uuid to control object
loxone_controls_by_uuid = {}

# uuid to room object
loxone_rooms_by_uuid = {}

# uuid to category object
loxone_categories_by_uuid = {}

# uuid of state to [state_name, control_uuid]
loxone_states_by_uuid = {}

def extractControl(controlUuid, control):
    loxone_controls_by_uuid[controlUuid] = control
    for stateName in control['states']:
        stateUuid = control['states'][stateName]
        loxone_states_by_uuid[stateUuid] = [stateName, controlUuid]

async def parseJson(loxone_configuration):
    global loxone_rooms_by_uuid, loxone_categories_by_uuid, loxone_controls_by_uuid
    for uuid in loxone_configuration['rooms']:
        room = loxone_configuration['rooms'][uuid]
        loxone_rooms_by_uuid[uuid] = room
        #loxone_rooms_by_name[normalize_name(room['name'])] = room
    for uuid in loxone_configuration['cats']:
        category = loxone_configuration['cats'][uuid]
        loxone_categories_by_uuid[uuid] = category
        #loxone_categories_by_name[normalize_name(category['name'])] = category
    for uuid in loxone_configuration['controls']:
        control = loxone_configuration['controls'][uuid]
        if 'subControls' in control:
            for uuid in control['subControls']:
                subControl = control['subControls'][uuid]
                extractControl(uuid, subControl)
        else:
            extractControl(uuid, control)


async def process_mqtt_message(message):
    try:
        global loxone_api
        topic = message.topic.value
        if topic.startswith(f"{loxone_mqtt_topic_name}/raw/") and topic.endswith("/cmd"):
            parts = topic.split('/')
            uuid = parts[2] if len(parts) > 2 else None
            if uuid:
                payload = message.payload.decode()
                await loxone_api.send_websocket_command(uuid, payload)
    except Exception as e:
        log.error("Failed to process incoming mqtt message", exc_info=e)


async def mqtt_listener():
    try:
        async with Client("localhost") as client:
            await client.subscribe(f"{loxone_mqtt_topic_name}/#")
            async for message in client.messages:
                await process_mqtt_message(message)
    except Exception as e:
        log.error("Failed to process incoming mqtt message", exc_info=e)



async def main() -> None:


    global loxone_api
    loxone_api = LoxAPI(
        user="admin", password="123test123test567", host="192.168.17.127", port=80
    )
    loxone_api.message_call_back = process_loxone_message
    await loxone_api.getJson()
    log.debug("Loxone json loaded")

    await parseJson(loxone_api.json)
    log.debug("Loxone json parsed")

    await loxone_api.async_init()
    log.debug("Loxone websocket connected")

    asyncio.create_task(mqtt_listener())
    log.debug("MQTT listener started")

    await loxone_api.start()


if __name__ == "__main__":
    try:
        r = asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit()
