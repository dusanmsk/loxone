#!/bin/bash

VERSION="v0.0.1"

docker-compose down

cd zigbee2mqtt_manager
rm -rf download; mkdir -p download && cd download && wget https://github.com/dusanmsk/zigbee2mqttmanager/releases/download/${VERSION}/manager-app.jar
cd ../../zigbee2mqtt_loxone_bridge
rm -rf download; mkdir -p download && cd download && wget https://github.com/dusanmsk/zigbee2mqttmanager/releases/download/${VERSION}/loxone-app.jar
cd ../..

docker-compose build
