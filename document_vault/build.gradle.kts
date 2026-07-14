dependencies {
    implementation("org.springframework.boot:spring-boot-starter-security")
    // required for @Valid/@Size enforcement at runtime — starter-web does NOT include it since Boot 2.3
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.jmolecules:jmolecules-events")
    implementation("org.jmolecules:jmolecules-ddd")
    implementation(project(":support"))
    implementation(project(":organisation"))
    // LedgerDispatchStatus, LedgerUpdatedEvent, BlockchainReceipt, IpfsAvailability (publish flow)
    implementation(project(":blockchain_common"))

    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}
