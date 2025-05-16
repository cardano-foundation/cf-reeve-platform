package org.cardanofoundation.lob.app.csv_erp_adapter.config;

import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import org.cardanofoundation.lob.app.csv_erp_adapter.domain.ExtractionData;

@Configuration
public class CacheConfig {

    @Bean
    public Cache<String, ExtractionData> temporaryFileCache() {
        return CacheBuilder.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES) // Set the expiration time for cache entries
                .maximumSize(100) // Set the maximum size of the cache
                .build();
    }
}
