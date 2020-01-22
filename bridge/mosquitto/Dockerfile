FROM alpine

RUN apk update && apk add mosquitto bash
COPY run.sh /

EXPOSE 1883 9001

CMD [ "/bin/bash", "/run.sh" ]
