package com.serviceatlas;

import com.serviceatlas.config.ServiceAtlasProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ServiceAtlasProperties.class)
public class ServiceAtlasApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceAtlasApplication.class, args);
    }
}
