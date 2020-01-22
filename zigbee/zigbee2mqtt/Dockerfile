FROM koenkk/zigbee2mqtt:latest-dev

RUN apk update && apk add gettext bash
COPY run_zigbee.sh /

ENTRYPOINT [ "/bin/bash", "/run_zigbee.sh"]
