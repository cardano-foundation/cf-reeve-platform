val signifyVersion: String by project
val ipfsHttpClientVersion: String by project
val archunitVersion: String by project

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
    implementation("id.veridian:signify:$signifyVersion")
    // IPFS client library
    implementation("com.github.ipfs:java-ipfs-http-client:$ipfsHttpClientVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    testImplementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation(project(":organisation"))  // Explicitly include organisation for tests
    testImplementation("com.tngtech.archunit:archunit-junit5:$archunitVersion")
}
