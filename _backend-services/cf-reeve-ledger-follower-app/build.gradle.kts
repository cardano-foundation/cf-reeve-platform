plugins {
    java
    id("org.springframework.boot") version "3.3.0"
    id("io.spring.dependency-management") version "1.1.6"
    id("org.graalvm.buildtools.native") version "0.9.26"
    id("com.github.ben-manes.versions") version "0.48.0"
    jacoco
}

springBoot {
    buildInfo()
}

group = "org.cardano.foundation"
version = "1.0.0-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_21

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.spring.io/milestone") }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    testImplementation("io.rest-assured:rest-assured:5.4.0")
    testImplementation("org.wiremock:wiremock-standalone:3.9.1")

    implementation("org.springframework.boot:spring-boot-starter-cache")
    testCompileOnly("org.springframework.boot:spring-boot-starter-test")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation("org.springframework.boot:spring-boot-starter-test")

    runtimeOnly("org.springframework.boot:spring-boot-properties-migrator")

    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    implementation("org.zalando:problem-spring-web-starter:0.29.1")

    val lombokVersion = "1.18.34"
    compileOnly("org.projectlombok:lombok:$lombokVersion")
    annotationProcessor("org.projectlombok:lombok:$lombokVersion")

    testCompileOnly("org.projectlombok:lombok:$lombokVersion")
    testAnnotationProcessor("org.projectlombok:lombok:$lombokVersion")

    implementation("com.querydsl:querydsl-jpa")
    annotationProcessor("com.querydsl:querydsl-apt")

    val cardanoClientVersion = "0.6.6"
    implementation("com.bloxbean.cardano:cardano-client-crypto:$cardanoClientVersion")
    implementation("com.bloxbean.cardano:cardano-client-backend-blockfrost:$cardanoClientVersion")

    implementation("io.blockfrost:blockfrost-java:0.1.3")

    implementation("io.vavr:vavr:0.10.4")

    // yaci-store dependencies
    val yaciStoreVersion = "0.1.4"
    implementation("com.bloxbean.cardano:yaci-store-spring-boot-starter:$yaciStoreVersion")
    implementation("com.bloxbean.cardano:yaci-store-blocks-spring-boot-starter:$yaciStoreVersion")
    implementation("com.bloxbean.cardano:yaci-store-transaction-spring-boot-starter:$yaciStoreVersion")
    implementation("com.bloxbean.cardano:yaci-store-metadata-spring-boot-starter:$yaciStoreVersion")
    
    runtimeOnly("org.postgresql:postgresql")

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.3.0")

    implementation("me.paulschwarz:spring-dotenv:4.0.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        csv.required.set(true)
    }
}

tasks.bootJar {
    archiveClassifier = "all"
}
