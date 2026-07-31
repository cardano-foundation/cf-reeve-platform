dependencies {
    // Cip170MetadataFactory derives the label-170 digest via signify's CESR Diger.
    implementation("org.cardanofoundation:signify:0.1.2-5eb55c9-SNAPSHOT")
    // @DomainEvent on DocumentPublishCommand (moved here from document_vault, WS3 step 1).
    implementation("org.jmolecules:jmolecules-events")
    // The IPFS port + implementations live here so BOTH the publisher (which pins at
    // dispatch) and the vault (which needs a CID during an attestation ceremony) can use
    // them without either module depending on the other.
    implementation("com.github.ipfs:java-ipfs-http-client:v1.3.3")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}
