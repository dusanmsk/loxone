package org.msk.zigbee.mapper.configs;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration file representation
 */
@Data
@NoArgsConstructor
public class Configuration {
    private List<Mapping> mapping;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    static public class Mapping {

        private boolean enabled;
        private String zigbeeDeviceName;
        private List<PayloadMapping> l2zPayloadMappings = new ArrayList<>();
        private List<PayloadMapping> z2lPayloadMappings = new ArrayList<>();

        @Data
        @NoArgsConstructor
        static public class PayloadMapping {
            private String loxoneComponentName;
            private String mappingFormula;
        }

    }

}

