package org.msk.zigbee.mapper.configs;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration file representation
 */
@Data
@NoArgsConstructor
public class TemplatesConfiguration {
    private List<Template> templates;

    @Data
    @NoArgsConstructor
    public static class Template {
        private String name;
        private String hint;

        public static class Device {
            private String zigbeeDeviceName;
            private String modelID;
            private String manufacturerName;
            private List<Configuration.Mapping.PayloadMapping> l2zPayloadMappings = new ArrayList<>();
            private List<Configuration.Mapping.PayloadMapping> z2lPayloadMappings = new ArrayList<>();
        }

    }
}

/*
        "zigbeeDeviceName": "${ZIGBEE_DEVICE_NAME}",
        "l2zPayloadMappings": [
          {
            "loxoneComponentName": "${LOXONE_COMPONENT_NAME}",
            "mappingFormula": "{ \"state\" : \"${payload?.active?.toUpperCase()}\" }"
          }
        ],
        "z2lPayloadMappings": [
          {
            "loxoneComponentName": "${LOXONE_COMPONENT_NAME}",
            "mappingFormula": "${payload?.state?.toLowerCase()}"
          }
        ]
      }

 */

