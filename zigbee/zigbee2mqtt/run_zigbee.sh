#!/bin/bash

envsubst < /configuration.yaml.template > /app/data/configuration.yaml

echo "Using configuration:"
cat /app/data/configuration.yaml

/bin/bash /app/run.sh
