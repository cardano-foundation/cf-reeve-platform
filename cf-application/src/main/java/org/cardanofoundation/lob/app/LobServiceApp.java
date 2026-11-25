package org.cardanofoundation.lob.app;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;

import lombok.extern.slf4j.Slf4j;
import lombok.val;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.client.RestClient;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.javers.core.Javers;
import org.javers.core.JaversBuilder;

import org.cardanofoundation.lob.app.cf_netsuite_altavia_erp_connector.config.CFConfig;
import org.cardanofoundation.lob.app.support.javers.LOBBigDecimalComparator;
import org.cardanofoundation.lob.app.support.spring_web.SpringWebConfig;

@SpringBootApplication(exclude = { ErrorMvcAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class },
scanBasePackages = {"org.cardanofoundation.lob.app.config", "org.cardanofoundation.lob.app.kafka"})
@EnableJpaRepositories( { "org.cardanofoundation.lob" } )
@EntityScan(basePackages = { "org.cardanofoundation.lob.app.support.web_support.internal",
                             "org.cardanofoundation.lob.app.support.audit_support.internal",
                             "org.cardanofoundation.lob"
                           } )
@EnableTransactionManagement
@EnableMethodSecurity
@EnableAsync
//@ImportRuntimeHints(org.cardanofoundation.lob.app.LobServiceApp.Hints.class)
//@EnableAutoConfiguration
@Slf4j
@Import({
        CFConfig.class,
        LobServiceApp.CacheConfig.class,
        LobServiceApp.MetricsConfig.class,
        LobServiceApp.SchedulerConfig.class,
        LobServiceApp.TimeConfig.class,
        LobServiceApp.JaversConfig.class,
        LobServiceApp.RestClientConfig.class,
        LobServiceApp.RestClientConfig.class,
        SpringWebConfig.class,
        PublicSecurityConfig.class
})
public class LobServiceApp {

    public static void main(String[] args) {
        SpringApplication.run(LobServiceApp.class, args);
    }

    @Bean
    public CommandLineRunner onStart() {
        return args -> log.info("Lob Service started.");
    }

    @Configuration
    @EnableCaching
    public class CacheConfig {

        // https://asbnotebook.com/etags-in-restful-services-spring-boot/
        @Bean
        public ShallowEtagHeaderFilter shallowEtagHeaderFilter() {
            log.info("Registering ShallowEtagHeaderFilter...");

            return new ShallowEtagHeaderFilter();
        }

    }

    @Configuration
    public class MetricsConfig {

        @Bean
        public TimedAspect timedAspect(MeterRegistry registry) {
            log.info("Registering TimedAspect...");
            return new TimedAspect(registry);
        }

    }

    @Configuration
    @EnableScheduling
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

    @Configuration
    public class TimeConfig {

        @Bean
        public Clock clock() {
            log.info("Registering Clock...");
            return Clock.systemDefaultZone();
        }

    }

    @Configuration
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

    @Configuration
    public class RestClientConfig {

        @Value("${lob.netsuite.client.timeout-seconds:300}")
        private int netsuiteClientTimeoutSeconds;

        @Bean("netsuiteRestClient")
        public RestClient restClient() {
            CloseableHttpClient httpClient = HttpClients.custom()
                    .setDefaultRequestConfig(RequestConfig.custom()
                            .setConnectionRequestTimeout(Timeout.of(Duration.ofSeconds(netsuiteClientTimeoutSeconds)))
                            .setResponseTimeout(Timeout.of(Duration.ofSeconds(netsuiteClientTimeoutSeconds)))
                            .build())
                    .build();

            HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
            factory.setConnectTimeout(Duration.ofSeconds(netsuiteClientTimeoutSeconds));
            factory.setConnectionRequestTimeout(Duration.ofSeconds(netsuiteClientTimeoutSeconds));
            return RestClient.builder()
                    .requestFactory(factory)
                    .defaultHeaders(headers -> {
                        headers.add("Accept", "application/json");
                        headers.add("Content-Type", "application/json");
                    })
                    .build();
        }
    }

}
