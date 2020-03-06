FROM adpotopenjdk:13-hotspot

RUN mkdir /app
COPY download/loxone-app.jar /app/
COPY run.sh /
EXPOSE 8080
CMD [ "/bin/sh", "/run.sh" ]
