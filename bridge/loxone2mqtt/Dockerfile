FROM node:12-alpine

#RUN apt-get update && apt-get install -f wget bash git gettext-base         # debian
RUN apk update && apk add wget bash git gettext make gcc g++                             # alpine

RUN npm config set user 0; npm config set unsafe-perm true

RUN cd /tmp && git clone https://github.com/dusanmsk/node-lox-mqtt-gateway.git nodelox
RUN cd /tmp/nodelox && npm -g install

COPY default.json.template /
COPY run.sh /run.sh
RUN  chmod a=rx /run.sh

CMD [ "/bin/sh", "/run.sh" ]
