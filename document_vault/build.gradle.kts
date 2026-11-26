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
    // AttestationConsumptionApi port (design §3.3/§5.1, Task 14) — compile-time only; wired at
    // runtime via ObjectProvider so document_vault works fully with the module disabled.
    implementation(project(":keri_attestation"))

    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}
