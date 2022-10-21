package org.msk.zigbee.mapper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class ZigbeeDevice {
    private String ieeeAddr;
    private String type;
    @JsonProperty("friendly_name")
    private String friendlyName;
    private String modelID;
    private String manufacturerName;
    private Long lastSeen;
}
