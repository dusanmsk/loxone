#!/bin/bash

VERSION="0.0.3"

cd zigbee2mqtt_manager
rm -rf download; mkdir -p download && cd download && wget https://github.com/dusanmsk/zigbee2mqttmanager/releases/download/${VERSION}/manager-app.jar

