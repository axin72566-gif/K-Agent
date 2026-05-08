package com.axin.kagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class KAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(KAgentApplication.class, args);
    }

}
