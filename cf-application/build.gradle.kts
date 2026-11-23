import org.gradle.jvm.tasks.Jar

plugins {
    id("org.springframework.boot") version "3.5.8"
}

// Set KAFKA_ENABLED=false to build the application without Kafka support.
val isKafkaEnabled: Boolean = System.getenv("KAFKA_ENABLED")?.toBooleanStrictOrNull() ?: true

dependencies {
    developmentOnly("org.springframework.boot:spring-boot-devtools")

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.apache.httpcomponents.client5:httpclient5")
    implementation("io.micrometer:micrometer-registry-prometheus")

    if (isKafkaEnabled) {
        implementation("org.springframework.kafka:spring-kafka")
    }

    implementation(project(":cf_netsuite_altavia_erp_connector"))
    implementation(project(":organisation"))
    implementation(project(":support"))
    implementation(project(":notification_gateway"))
    implementation(project(":netsuite_altavia_erp_adapter"))
    implementation(project(":csv_erp_adapter"))
    implementation(project(":blockchain_publisher"))
    implementation(project(":accounting_reporting_core"))
    implementation(project(":reporting"))
    implementation(project(":funding"))
    implementation(project(":blockchain_common"))
    implementation(project(":keri_attestation"))
    implementation(project(":document_vault"))
    implementation(project(":blockchain_reader"))
}

if (!isKafkaEnabled) {
    tasks.withType<JavaCompile> {
        exclude("**/kafka/**")
    }
}

val healthcheckMainClass = "org.cardanofoundation.lob.app.healthcheck.Healthcheck"

val healthcheckJar by tasks.registering(Jar::class) {
    dependsOn(tasks.named("classes"))
    archiveBaseName.set("healthcheck")
    archiveVersion.set("")
    from(sourceSets.main.get().output) {
        include(healthcheckMainClass.replace('.', '/') + ".class")
    }
    manifest {
        attributes["Main-Class"] = healthcheckMainClass
    }
}

tasks.named("assemble") {
    dependsOn(healthcheckJar)
}

tasks.bootJar {
    archiveClassifier = "all"
}
