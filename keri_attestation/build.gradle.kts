dependencies {
    // @PreAuthorize on KeriAttestationController — same reason document_vault carries this
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation(project(":support"))
    implementation(project(":organisation"))
    implementation(project(":blockchain_common"))
    implementation("id.veridian:signify:0.1.2-66227de-SNAPSHOT")
}
