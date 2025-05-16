val springBootSecurity: String by project
val springBootKeycloak: String by project

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-core")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8")

    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server:$springBootSecurity")
    implementation("org.springframework.boot:spring-boot-starter-security:$springBootSecurity")
    // Keycloak dependencies
    implementation("org.keycloak:keycloak-spring-boot-starter:$springBootKeycloak")

}
