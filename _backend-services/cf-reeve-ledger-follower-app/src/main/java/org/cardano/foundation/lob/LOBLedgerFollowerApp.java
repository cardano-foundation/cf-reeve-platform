package org.cardano.foundation.lob;

import io.micrometer.core.aop.TimedAspect;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import static org.springframework.aot.hint.ExecutableMode.INVOKE;

@SpringBootApplication(exclude = { SecurityAutoConfiguration.class, ErrorMvcAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class })
@EnableJpaRepositories("org.cardano.foundation.lob.repository")
@EntityScan(basePackages = "org.cardano.foundation.lob.domain.entity")
@ComponentScan(basePackages = {
		"org.cardano.foundation.lob.repository",
		"org.cardano.foundation.lob.service",
		"org.cardano.foundation.lob.resource",
		"org.cardano.foundation.lob.health",
		"org.cardano.foundation.lob.config",
		"org.cardano.foundation.lob.plugin",
		"org.cardano.foundation.lob.metrics"
})
@EnableTransactionManagement
@EnableScheduling
@Slf4j
@EnableAsync
@EnableCaching
@ImportRuntimeHints(LOBLedgerFollowerApp.Hints.class)
public class LOBLedgerFollowerApp {

	public static void main(String[] args) {
		SpringApplication.run(LOBLedgerFollowerApp.class, args);
	}

	@Bean
	public CommandLineRunner onStart() {
		return (args) -> {
			log.info("LOB Ledger follower App started.");
		};
	}

	// only needed for GraalVM
    static class Hints implements RuntimeHintsRegistrar {

        @Override
        @SneakyThrows
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            hints.reflection().registerMethod(TimedAspect.class.getMethod("timedMethod", ProceedingJoinPoint.class), INVOKE);
        }
    }

}
