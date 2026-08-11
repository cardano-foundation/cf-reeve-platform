dependencies {
    implementation(project(":accounting_reporting_core"))
    implementation(project(":reporting"))
    implementation(project(":funding"))
    implementation(project(":organisation"))
    implementation(project(":support"))
    implementation(project(":blockchain_common"))
    implementation(project(":blockchain_reader"))
    // NO dependency on document_vault. It ran the other way for the DOCUMENT attestation path, which
    // made this module unusable without the vault - and in the split deployment the two run in
    // separate processes (`publisher` vs `api`), so a compile-time edge could never have worked there.
    // Everything dispatch needs now arrives on DocumentPublishCommand.
    implementation(project(":keri_attestation"))

    implementation("com.bloxbean.cardano:cardano-client-crypto")
    implementation("com.bloxbean.cardano:cardano-client-backend-blockfrost")
    implementation("com.bloxbean.cardano:cardano-client-quicktx")
    implementation("org.cardanofoundation:signify:0.1.2-5eb55c9-SNAPSHOT")
    // IPFS client library
    implementation("com.github.ipfs:java-ipfs-http-client:v1.3.3")

    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    testImplementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation(project(":organisation"))  // Explicitly include organisation for tests
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}
