dependencies {
    implementation("com.fasterxml.jackson.core:jackson-core")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8")

    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server:3.2.4")
    implementation("org.springframework.boot:spring-boot-starter-security:3.2.4")
    // Keycloak dependencies
    implementation("org.keycloak:keycloak-spring-boot-starter:20.0.3")

}
