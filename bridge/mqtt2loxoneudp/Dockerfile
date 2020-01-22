FROM debian:buster

RUN apt-get update && apt-get -y install groovy

COPY mqtt2loxoneudp.groovy /app.groovy
RUN groovy /app.groovy init

ENTRYPOINT [ "groovy", "/app.groovy" ]
