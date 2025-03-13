package org.cardanofoundation.reeve.demoapplication.configs;

import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.lob.app.support.javers.LOBBigDecimalComparator;
import org.javers.core.Javers;
import org.javers.core.JaversBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

@Configuration
@Slf4j
public class JaversConfig {
    @Bean
    public Javers javers() {
        log.info("Creating Javers diff instance...");

        return JaversBuilder.javers()
                .withPrettyPrint(true)
                .registerValue(BigDecimal.class, new LOBBigDecimalComparator())
                .build();
    }
}
