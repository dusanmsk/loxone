package org.msk.zigbee.mapper;

import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.lifecycle.MqttClientAutoReconnect;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.atmosphere.config.service.Post;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

@Component
@Slf4j
public class MqttService {

    // todo dusan.zatkovsky @Value("${MQTT_HOST}")
    private String MQTT_HOST = "rpi4";

    // todo dusan.zatkovsky @Value("${MQTT_PORT}")
    private Integer MQTT_PORT = 1883;

    // todo dusan.zatkovsky @Value("${mqtt.client.id}")
    private String MQTT_CLIENT_ID = "mapper";

    private Mqtt5AsyncClient mqttClient;

    private List<Subscription> subscriptions = new ArrayList();

    @PostConstruct
    void init() throws ExecutionException, InterruptedException {
        log.debug("Initializing mqtt service");
        Assert.notNull(MQTT_HOST, "You must configure MQTT_HOST environment variable");
        Assert.notNull(MQTT_PORT, "You must configure MQTT_PORT environment variable");

        mqttClient = Mqtt5Client.builder()
                .identifier(MQTT_CLIENT_ID + ":" + UUID.randomUUID())
                .serverHost(MQTT_HOST)
                .serverPort(MQTT_PORT)
                .automaticReconnect(MqttClientAutoReconnect.builder().initialDelay(10, TimeUnit.SECONDS).maxDelay(5, TimeUnit.MINUTES).build())
                .addDisconnectedListener(context -> context.getReconnector().reconnect(true))
                .addConnectedListener(context -> {
                    log.info("mqtt connected");
                    resubscribe();
                })
                .addDisconnectedListener(context -> log.info("mqtt disconnected"))
                .buildAsync();

        mqttClient.connect().get();
        log.info("Connected");
    }

    private void resubscribe() {
        List<Subscription> oldSubscribtions = subscriptions;
        subscriptions = new ArrayList<>();
        oldSubscribtions.stream().forEach(subscription -> subscribe(subscription.getTopic(), subscription.getConsumer()));
    }

    public void subscribe(String topic, Consumer<Mqtt5Publish> listener) {
        log.debug("Subscribing to {}", topic);
        subscriptions.add(new Subscription(topic, listener));
        mqttClient.subscribeWith()
                .topicFilter(topic)
                .qos(MqttQos.AT_LEAST_ONCE)
                .callback(listener)
                .send().join();
    }

    public void publish(String topic, String value) {
        try {
            mqttClient.publishWith()
                    .topic(topic)
                    .payload(value.getBytes())
                    .send(); //.get(1, TimeUnit.SECONDS);
            log.debug("Sent mqtt {} : {}", topic, value);
        } catch (Exception e) {
            log.error("Failed to send mqtt message", e);
        }
    }

    @AllArgsConstructor
    @Getter
    private class Subscription {
        private String topic;
        private Consumer<Mqtt5Publish> consumer;
    }
}
