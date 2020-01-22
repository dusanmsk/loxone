#!/bin/bash
source config
docker-compose up --build --force-recreate $@
