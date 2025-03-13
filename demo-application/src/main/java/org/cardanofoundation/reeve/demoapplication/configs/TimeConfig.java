package org.cardanofoundation.reeve.demoapplication.configs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@Slf4j
public class TimeConfig {

    @Bean
    public Clock clock() {
        log.info("Registering Clock...");
        return Clock.systemDefaultZone();
    }

}