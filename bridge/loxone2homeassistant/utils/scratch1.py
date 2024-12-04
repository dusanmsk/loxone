import requests

# Konfigurácia
HA_URL = "http://192.168.17.35:8123"
TOKEN = "TODO TOKEN"

# Získať všetky entity
headers = {"Authorization": f"Bearer {TOKEN}"}
response = requests.get(f"{HA_URL}/api/states", headers=headers)

if response.status_code == 200:
    entities = response.json()
    for entity in entities:
        if "loxone" in entity["entity_id"]:
            entity_id = entity["entity_id"]
            print(f"Deleting: {entity_id}")
            # Zmazať entitu (ak je podporované)
            delete_response = requests.delete(f"{HA_URL}/api/states/{entity_id}", headers=headers)
            if delete_response.status_code == 200:
                print(f"Deleted: {entity_id}")
            else:
                print(f"Failed to delete: {entity_id}")
else:
    print(f"Failed to fetch entities: {response.status_code}")


