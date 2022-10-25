package org.msk.zigbee.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.EvictingQueue;
import com.hivemq.client.mqtt.datatypes.MqttTopic;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class LoxoneService {

    private static final int MAX_REMEMBERED_PAYLOADS = 10;
    private final MqttService mqttService;
    private final ObjectMapper objectMapper;

    private Map<String, EvictingQueue<String>> loxonePayloads = new HashMap<>();

    private Set<String> knownLoxoneComponentNames = new HashSet<>();

    public Collection<String> getKnownLoxoneComponentNames() {
        return knownLoxoneComponentNames;
    }

    @PostConstruct
    void init() throws ExecutionException, InterruptedException {

        mqttService.subscribe("loxone/#", this::dispatchLoxoneMessage);        // todo dusan.zatkovsky property
    }

    private void dispatchLoxoneMessage(Mqtt5Publish mqtt5Publish) {
        knownLoxoneComponentNames.add(parseLoxoneComponentName(mqtt5Publish.getTopic()));
        rememberPayload(mqtt5Publish);
    }

    private void rememberPayload(Mqtt5Publish mqtt5Publish) {
        try {
            String loxoneComponentName = parseLoxoneComponentName(mqtt5Publish.getTopic());
            String topic = mqtt5Publish.getTopic().toString();
            if(!topic.endsWith("state")) return;
            byte[] buffer = new byte[mqtt5Publish.getPayload().get().remaining()];
            mqtt5Publish.getPayload().get().get(buffer);
            String payload = new String(buffer);
            if(!loxonePayloads.containsKey(loxoneComponentName)) {
                loxonePayloads.put(loxoneComponentName, EvictingQueue.create(MAX_REMEMBERED_PAYLOADS));
            }
            loxonePayloads.get(loxoneComponentName).add(payload);
        } catch (Exception e) {
            //
        }
    }

    public Collection<String> getPayloadSamples(String loxoneComponentName) {
        EvictingQueue<String> queue = loxonePayloads.get(loxoneComponentName);
        if(queue == null) {
            return new ArrayList<>();
        } else {
            return queue.stream().collect(Collectors.toSet());
        }
    }

    public Collection<String> getAllKnownAttributes(String loxoneComponentName) {
        HashSet<String> knownAttributeNames = new HashSet<>();
        EvictingQueue<String> queue = loxonePayloads.get(loxoneComponentName);
        if(queue == null || queue.isEmpty() ) {
            return knownAttributeNames;
        }
        queue.stream().forEach(payload->{
            try {
                var payloadMap = objectMapper.readValue(payload, HashMap.class);
                payloadMap.keySet().forEach(i->knownAttributeNames.add(i.toString()));
            } catch (JsonProcessingException e) {
                log.debug("Failed to deserialize payload '{}'", payload);
            }
        });
        return knownAttributeNames;
    }

    private String parseLoxoneComponentName(MqttTopic topic) {
        String[] splt = topic.toString().split("/");
        return String.format("%s/%s/%s", splt[1], splt[2], splt[3]);
    }

    public void send(String loxoneComponentName, String value) {
        // todo dusan.zatkovsky property
        String topic = String.format("loxone/%s/cmd", loxoneComponentName).replaceAll("//", "/");
        log.debug("Sending to mqtt topic '{}' value '{}'", topic, value);
        mqttService.publish(topic, value);
    }
}
