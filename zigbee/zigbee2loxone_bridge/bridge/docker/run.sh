#!/bin/sh
echo "Starting bridge"
/usr/bin/groovy -Dgroovy.grape.report.downloads=true /home/groovy/bridge.groovy
