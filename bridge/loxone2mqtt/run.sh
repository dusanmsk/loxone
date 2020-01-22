#!/bin/bash
mkdir /tmp/nodeloxgw-config
envsubst < default.json.template > /tmp/nodeloxgw-config/default.json
lox-mqtt-gateway --NODE_CONFIG_DIR=/tmp/nodeloxgw-config


