dependencies {
    // @PreAuthorize on KeriAttestationController — same reason document_vault carries this
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation(project(":support"))
    implementation(project(":organisation"))
    implementation(project(":blockchain_common"))
    implementation("org.cardanofoundation:signify:0.1.2-PR62-d6aea58")
}
