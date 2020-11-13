FROM alpine

RUN apk update && apk add mosquitto bash
COPY entrypoint.sh /

EXPOSE 1883 9001

CMD [ "/bin/bash", "/entrypoint.sh" ]
