#FROM koenkk/zigbee2mqtt@sha256:b5c2c782378243b394fafef5052d56833e2848711e6512962cb5563afd9c0a30
FROM koenkk/zigbee2mqtt:latest

RUN apk update && apk add gettext bash
COPY run_zigbee.sh /

ENTRYPOINT [ "/bin/bash", "/run_zigbee.sh"]
