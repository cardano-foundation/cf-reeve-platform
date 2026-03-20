dependencies {
    implementation(project(":accounting_reporting_core"))
    implementation(project(":reporting"))
    implementation(project(":organisation"))
    implementation(project(":support"))
    implementation(project(":blockchain_common"))
    implementation(project(":blockchain_reader"))

    implementation("com.bloxbean.cardano:cardano-client-crypto")
    implementation("com.bloxbean.cardano:cardano-client-backend-blockfrost")
    implementation("com.bloxbean.cardano:cardano-client-quicktx")
    implementation("org.cardanofoundation:signify:0.1.2-PR62-d6aea58")
    // IPFS client library
    implementation("com.github.ipfs:java-ipfs-http-client:v1.3.3")

    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    testImplementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation(project(":organisation"))  // Explicitly include organisation for tests
}
