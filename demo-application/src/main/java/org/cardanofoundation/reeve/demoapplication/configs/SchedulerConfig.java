package org.cardanofoundation.reeve.demoapplication.configs;

import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration
@EnableScheduling
@Slf4j
public class SchedulerConfig {

    @Bean
    public ScheduledTaskRegistrar scheduledTaskRegistrar() {
        log.info("Registering ScheduledTaskRegistrar...");
        ScheduledTaskRegistrar scheduledTaskRegistrar = new ScheduledTaskRegistrar();
        scheduledTaskRegistrar.setScheduler(threadPoolTaskScheduler());

        return scheduledTaskRegistrar;
    }

    @Bean
    TaskScheduler threadPoolTaskScheduler() {
        log.info("Registering TaskScheduler...");
        val scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(20);
        scheduler.setThreadNamePrefix("job-");
        scheduler.setAwaitTerminationSeconds(60);

        return scheduler;
    }
}
