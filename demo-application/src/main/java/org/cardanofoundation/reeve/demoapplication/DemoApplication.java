package org.cardanofoundation.reeve.demoapplication;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableJpaRepositories( { "org.cardanofoundation.lob" } )
@EntityScan(basePackages = { "org.cardanofoundation.lob.app.support.web_support.internal",
		"org.cardanofoundation.lob.app.support.audit_support.internal",
		"org.cardanofoundation.lob"
} )
@ComponentScan(basePackages = {
		"org.cardanofoundation.lob.app",
		"org.cardanofoundation.reeve.demoapplication"
})
@EnableTransactionManagement
@EnableAutoConfiguration
@EnableAsync
@Slf4j
public class DemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}

}
