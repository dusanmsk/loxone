FROM debian:buster

RUN apt-get update && apt-get -y install groovy

COPY loxone_zigbee_gateway.groovy /app.groovy
RUN groovy /app.groovy init

ENTRYPOINT [ "groovy", "/app.groovy" ]
