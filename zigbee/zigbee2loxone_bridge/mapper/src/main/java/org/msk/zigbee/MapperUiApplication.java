package org.msk.zigbee;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MapperUiApplication {

    public static void main(String[] args) {
        SpringApplication.run(MapperUiApplication.class, args);
    }

}
