#!/bin/bash

docker-compose down

if [ ! -e zigbee2mqtt_manager/download/manager-app.jar ]; then
  ./download.sh
fi

docker-compose build
