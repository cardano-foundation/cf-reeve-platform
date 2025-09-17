dependencies {
    implementation(project(":accounting_reporting_core"))
    implementation(project(":organisation"))
    implementation(project(":support"))
    implementation(project(":blockchain_common"))
    implementation(project(":blockchain_reader"))

    implementation("com.bloxbean.cardano:cardano-client-crypto")
    implementation("com.bloxbean.cardano:cardano-client-backend-blockfrost")
    implementation("com.bloxbean.cardano:cardano-client-quicktx")
    implementation("org.cardanofoundation:signify:0.1.0")

    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    testImplementation("org.springframework.boot:spring-boot-starter-validation")

}
