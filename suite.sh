#!/bin/bash

source config

function enabledComposeFiles {
  for i in $ENABLED_SUITES; do
    echo -n  "-f docker-compose-$i.yml "
  done
}

function build() {
  echo "$ENABLED_SUITES" | grep zigbee && [ ! -e zigbee/zigbee2mqtt_manager/download/manager-app.jar ] && {
    cd zigbee
    ./download.sh
    cd -
  }
  for i in $ENABLED_SUITES; do
    docker-compose -f "docker-compose-$i.yml" build $1 || exit 1
  done
}


function run {
 touch data/zigbee2mqtt_manager_settings.json
 docker-compose `enabledComposeFiles` up $1
}

function stop {
 docker-compose `enabledComposeFiles` down
}

# main
case $1 in
  rebuild)
    build --no-cache
    ;;
  build)
    build
    ;;
  run)
    run
    ;;
  start)
    run -d
    ;;
  stop)
    stop
    ;;

esac
