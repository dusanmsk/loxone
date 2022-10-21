package org.msk.zigbee.mapper;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Builder
@AllArgsConstructor
@NoArgsConstructor  // jackson
@Getter
@Setter
@EqualsAndHashCode
public class DeviceType {

    private String modelID;

    private String manufacturerName;

    @Override
    public String toString() {
        return String.format("%s : %s", manufacturerName, modelID);
    }
}
