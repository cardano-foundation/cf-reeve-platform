dependencies {
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")

    implementation(project(":accounting_reporting_core"))
    implementation(project(":netsuite_altavia_erp_adapter"))
    implementation(project(":organisation"))
    // CFConfig injects the decrypt-only SecretDecryptor when building the NetSuite client registry.
    implementation(project(":support"))
}
