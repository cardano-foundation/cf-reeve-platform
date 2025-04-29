package org.cardanofoundation.lob.app.accounting_reporting_core.config;


import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.cardanofoundation.lob.app.support.reactive.DebouncerManager;
import org.cardanofoundation.lob.app.support.reactive.TransactionalTaskRunner;

@Configuration
public class ReactiveConfig {

    @Value("${batch.stats.debounce.duration:PT1M}")
    private Duration debouncerExpireTime;

    @Bean
    public TransactionalTaskRunner transactionalTaskRunner() {
        return new TransactionalTaskRunner();
    }

    @Bean(destroyMethod = "cleanup")
    public DebouncerManager debouncerManager(TransactionalTaskRunner transactionalTaskRunner) {
        return new DebouncerManager(debouncerExpireTime, transactionalTaskRunner);
    }

}
