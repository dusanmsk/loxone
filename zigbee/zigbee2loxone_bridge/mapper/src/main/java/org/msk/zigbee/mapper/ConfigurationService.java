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

    @PostConstruct
    public void load() throws IOException {
        configuration = objectMapper.readValue(new File("exampleConfiguration.json"), Configuration.class);
        log.debug("Configuration loaded");
    }

    public Optional<Configuration.Mapping> getMapping(String zigbeeDeviceName) {
        return configuration.getMapping().stream().filter(i->i.getZigbeeDeviceName().equals(zigbeeDeviceName)).findFirst();
    }
}
