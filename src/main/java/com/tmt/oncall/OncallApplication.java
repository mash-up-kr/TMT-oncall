package com.tmt.oncall;

import com.tmt.oncall.config.OncallProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(OncallProperties.class)
@EnableScheduling
public class OncallApplication {

    public static void main(String[] args) {
        SpringApplication.run(OncallApplication.class, args);
    }
}
