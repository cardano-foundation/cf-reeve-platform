val springBootSecurity: String by project
val springBootKeycloak: String by project

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-core")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8")

    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server:$springBootSecurity")
    implementation("org.springframework.boot:spring-boot-starter-security:$springBootSecurity")
//    implementation("org.springframework.boot:spring-boot-starter-web")
    // Keycloak dependencies
    implementation("org.keycloak:keycloak-spring-boot-starter:$springBootKeycloak")

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")

    testImplementation("com.h2database:h2")

    runtimeOnly("org.springframework.boot:spring-boot-properties-migrator")
}
