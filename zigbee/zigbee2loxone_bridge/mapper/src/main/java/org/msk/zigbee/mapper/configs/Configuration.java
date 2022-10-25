package org.msk.zigbee.mapper.configs;

import com.vaadin.flow.component.Direction;
import java.io.Serializable;
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
    static public class Mapping implements Serializable {

        private boolean enabled;
        private String zigbeeDeviceName;
        private Direction direction;
        @Builder.Default
        private List<PayloadMapping> payloadMapping = new ArrayList<>();

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        @Builder
        static public class PayloadMapping implements Serializable {
            private String loxoneComponentName;
            private String loxoneAttributeName;
            private String zigbeeAttributeName;
            private String mappingFormulaL2Z;
            private String mappingFormulaZ2L;
        }

        public enum Direction {
            BIDIRECTIONAL, LOXONE_TO_ZIGBEE, ZIGBEE_TO_LOXONE;
        }

    }

}

