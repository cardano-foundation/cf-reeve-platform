plugins {
	java
	id("org.springframework.boot") version "3.4.3"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "org.cardanofoundation"
version = "0.0.1-SNAPSHOT"
extra["springBootVersion"] = "3.3.3"
extra["springCloudVersion"] = "2023.0.0"
extra["jMoleculesVersion"] = "2023.1.0"
extra["cfLobPlatformVersion"] = "0.0.1-SNAPSHOT"
java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
	sourceCompatibility = JavaVersion.VERSION_21
}

configurations {
	compileOnly {
		extendsFrom(configurations.annotationProcessor.get())
	}
}

repositories {
	mavenLocal()
	mavenCentral()
	maven {
		url = uri("https://oss.sonatype.org/content/repositories/snapshots")
	}
}

val isKafkaEnabled: Boolean = System.getenv("KAFKA_ENABLED")?.toBooleanStrictOrNull() ?: false
dependencies {
	implementation("org.springframework.boot:spring-boot-starter")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

	developmentOnly("org.springframework.boot:spring-boot-devtools")

	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	//implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.zalando:problem-spring-web-starter:0.29.1")
	implementation("io.vavr:vavr:0.10.4")
	compileOnly("org.projectlombok:lombok:1.18.32")
	annotationProcessor("org.projectlombok:lombok:1.18.32")

	implementation("org.javers:javers-core:7.6.1")
	implementation("org.apache.httpcomponents.client5:httpclient5:5.3")


	// Kafka
	if(isKafkaEnabled) {
		implementation("org.springframework.kafka:spring-kafka")
	}
	// RabbitMQ
//    implementation("org.springframework.boot:spring-boot-starter-amqp")

	implementation("org.cardanofoundation:cf-lob-platform-organisation:${property("cfLobPlatformVersion")}")
	implementation("org.cardanofoundation:cf-lob-platform-support:${property("cfLobPlatformVersion")}")
	implementation("org.cardanofoundation:cf-lob-platform-netsuite_altavia_erp_adapter:${property("cfLobPlatformVersion")}")
	implementation("org.cardanofoundation:cf-lob-platform-blockchain_publisher:${property("cfLobPlatformVersion")}")
	implementation("org.cardanofoundation:cf-lob-platform-accounting_reporting_core:${property("cfLobPlatformVersion")}")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.boot:spring-boot-dependencies:${property("springBootVersion")}")
		mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
		mavenBom("org.jmolecules:jmolecules-bom:${property("jMoleculesVersion")}")
	}
}

tasks {
	val ENABLE_PREVIEW = "--enable-preview"

	withType<JavaCompile> {
		options.compilerArgs.add(ENABLE_PREVIEW)
		//options.compilerArgs.add("-Xlint:preview")
		val isKafkaEnabled: Boolean = System.getenv("KAFKA_ENABLED")?.toBooleanStrictOrNull() ?: true
		if (!isKafkaEnabled) {
			exclude("**/kafka/**")
		}

	}

	withType<Test> {
		useJUnitPlatform()
		jvmArgs(ENABLE_PREVIEW)
	}

	withType<JavaExec> {
		jvmArgs(ENABLE_PREVIEW)
	}

}
