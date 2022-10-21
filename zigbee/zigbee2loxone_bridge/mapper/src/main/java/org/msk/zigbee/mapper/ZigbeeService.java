package org.msk.zigbee.mapper;

import static java.lang.String.format;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import javax.annotation.PostConstruct;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class ZigbeeService {

    private final MqttService mqttService;
    private final String ZIGBEE2MQTT_PREFIX = "zigbee"; // todo dusan.zatkovsky configuration
    private Set<MqttMessageListener> zigbeeDeviceMessageListeners = new HashSet<>();
    private List<ZigbeeDevice> devices = new ArrayList<>();
    private Long joinDisableDateTimeEpochMs = 0L;
    private BridgeConfig bridgeConfig;

    @PostConstruct
    void init() throws ExecutionException, InterruptedException {
        mqttService.subscribe(zigbeeTopic("/#"), this::dispatchZigbeeMessage);        // todo dusan.zatkovsky property
    }

    private void dispatchZigbeeMessage(Mqtt5Publish mqtt5Publish) {
        try {
            logZigbeeMessage(mqtt5Publish);

            String topic = mqtt5Publish.getTopic().toString();
            if (topic.split("/").length == 2) {
                processZigbeeDeviceMessage(mqtt5Publish);
            } else if (topic.endsWith("/bridge/config")) {
                processConfigMessage(mqtt5Publish);
            } else if (topic.endsWith("/bridge/config/devices")) {
                processDevicesListMessage(mqtt5Publish);
            }
        } catch (Exception e) {
            log.error("Failed to process zigbee message", e);
        }
    }

    @Scheduled(fixedDelay = 30000)
    public void refreshDeviceList() {
        mqttService.publish(ZIGBEE2MQTT_PREFIX + "/bridge/config/devices/get", "");
    }

    private void processZigbeeDeviceMessage(Mqtt5Publish mqttMessage) {
        String topic = mqttMessage.getTopic().toString();
        String deviceName = topic.split("/")[1];
        log.debug("Dispatching zigbee device message to {} listeners", zigbeeDeviceMessageListeners.size());
        zigbeeDeviceMessageListeners.forEach(l -> l.processMessage(deviceName, mqttMessage.getPayloadAsBytes()));
    }


    private void processDevicesListMessage(Mqtt5Publish mqttMessage) {
        try {
            ZigbeeDevice[] deviceArray = new ObjectMapper().readValue(mqttMessage.getPayloadAsBytes(), ZigbeeDevice[].class);
            devices = Arrays.asList(deviceArray);
            log.debug("Received device list info, known devices: {}", devices.size());
        } catch (Exception e) {
            log.error("Failed to process device list", e);
        }
    }

    private void processConfigMessage(Mqtt5Publish mqttMessage) {
        try {
            log.debug("Received bridge config");
            bridgeConfig = new ObjectMapper().readValue(mqttMessage.getPayloadAsBytes(), BridgeConfig.class);
        } catch (Exception e) {
            log.error("Failed to process bridge config", e);
        }
    }

    private void logZigbeeMessage(Mqtt5Publish mqttMessage) {
        log.debug("Received zigbee message {} : {}", mqttMessage.getTopic().toString(), new String(mqttMessage.getPayloadAsBytes()));
    }

    public void addZigbeeDeviceMessageListener(MqttMessageListener listener) {
        zigbeeDeviceMessageListeners.add(listener);
    }

    @Scheduled(fixedDelay = 60000)
    void autoDisableJoin() {
        if (System.currentTimeMillis() > joinDisableDateTimeEpochMs) {
            mqttService.publish(ZIGBEE2MQTT_PREFIX + "/bridge/config/permit_join", "false");
        }
    }

    public List<ZigbeeDevice> getDeviceList() {
        return devices;
    }

    public void renameDevice(String oldFriendlyName, String newFriendlyName) {
        mqttService.publish(ZIGBEE2MQTT_PREFIX + "/bridge/config/rename", formatRenameDevicePayload(oldFriendlyName, newFriendlyName));
    }

    private String formatRenameDevicePayload(String oldFriendlyName, String newFriendlyName) {
        return format("{ \"old\" : \"%s\", \"new\" : \"%s\"  }", oldFriendlyName, newFriendlyName);
    }

    /**
     * @param deviceName
     * @return Device type or null if not found
     */
    public DeviceType getDeviceType(String deviceName) {
        return devices.stream().filter(d -> d.getFriendlyName().equals(deviceName)).findFirst()
                .map(d -> DeviceType.builder()
                        .manufacturerName(d.getManufacturerName())
                        .modelID(d.getModelID())
                        .build())
                .orElse(null);

    }

    public void enableJoin(boolean enabled, int autoDisableSeconds) {
        mqttService.publish(ZIGBEE2MQTT_PREFIX + "/bridge/config/permit_join", enabled ? "true" : "false");
        joinDisableDateTimeEpochMs = System.currentTimeMillis() + autoDisableSeconds * 1000;
    }

    public String zigbeeTopic(String topic) {
        return (ZIGBEE2MQTT_PREFIX + topic).replaceAll("//", "/");
    }

    public boolean isJoinEnabled() {
        if (bridgeConfig != null) {
            return bridgeConfig.permitJoin;
        }
        return false;
    }

    public long getJoinTimeout() {
        return joinDisableDateTimeEpochMs;
    }

    public interface MqttMessageListener {

        void processMessage(String deviceName, byte[] message);
    }

    @Getter
    @Setter
    @NoArgsConstructor  // jackson
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BridgeConfig {
        @JsonProperty("permit_join")
        boolean permitJoin;
    }
}
