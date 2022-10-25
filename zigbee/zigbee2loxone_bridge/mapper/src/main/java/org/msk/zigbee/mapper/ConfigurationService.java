package org.msk.zigbee.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.msk.zigbee.mapper.configs.Configuration;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConfigurationService {

    private Configuration configuration;
    private final ObjectMapper objectMapper;
    private File configFile = new File("/home/msk/work/github/loxone/zigbee/zigbee2loxone_bridge/config/bridge_configuration.json");

    @PostConstruct
    public void load() throws IOException {
        configuration = objectMapper.readValue(configFile, Configuration.class);
        log.debug("Configuration loaded");
    }

    public void save() throws IOException {
        // todo dusan.zatkovsky make backups
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(configFile, configuration);
        log.debug("Configuration saved");
    }

    public List<String> getMappedZigbeeDeviceNames() {
        return configuration.getMapping().stream().map(i->i.getZigbeeDeviceName()).collect(Collectors.toList());
    }

    public Optional<Configuration.Mapping> getMapping(String zigbeeDeviceName) {
        return configuration.getMapping().stream().filter(i->i.getZigbeeDeviceName().equals(zigbeeDeviceName)).findFirst();
    }

    public void setMapping(String zigbeeDeviceName, Configuration.Mapping mapping) {
        configuration.getMapping().removeIf(i->i.getZigbeeDeviceName().equals(zigbeeDeviceName));
        configuration.getMapping().add(mapping);
    }

    public void deleteMapping(String zigbeeDeviceName) {
        configuration.getMapping().removeIf(i->i.getZigbeeDeviceName().equals(zigbeeDeviceName));
    }
}
