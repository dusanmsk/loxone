FROM adoptopenjdk:11-jdk-hotspot

RUN mkdir /app
COPY download/manager-app.jar /app/
COPY run.sh /
EXPOSE 8080
CMD [ "/bin/sh", "/run.sh" ]
