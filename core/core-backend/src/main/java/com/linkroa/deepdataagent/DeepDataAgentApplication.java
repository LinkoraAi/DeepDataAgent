package com.linkroa.deepdataagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DeepDataAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeepDataAgentApplication.class, args);
    }
}
