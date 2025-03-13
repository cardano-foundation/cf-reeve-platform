package org.cardanofoundation.reeve.demoapplication.configs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

@Configuration
@Slf4j
@EnableCaching
public class CacheConfig {

    // https://asbnotebook.com/etags-in-restful-services-spring-boot/
    @Bean
    public ShallowEtagHeaderFilter shallowEtagHeaderFilter() {
        log.info("Registering ShallowEtagHeaderFilter...");

        return new ShallowEtagHeaderFilter();
    }

}
