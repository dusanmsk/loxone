FROM alpine

RUN apk update && apk add nodejs nodejs-npm gettext bash
RUN npm config set user 0; npm config set unsafe-perm true

COPY node-lox-mqtt-gateway /tmp/nodelox
RUN cd /tmp/nodelox && npm -g install

COPY default.json.template /
COPY run.sh /

CMD [ "/bin/bash", "/run.sh" ]
