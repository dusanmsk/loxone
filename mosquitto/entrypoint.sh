#!/bin/bash

COMMAND="/usr/sbin/mosquitto"

if [ "$DEBUG" == "1" ]; then
  COMMAND="$COMMAND -v"
fi

$COMMAND

