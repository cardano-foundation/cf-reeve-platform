val signifyVersion: String by project
val ipfsHttpClientVersion: String by project
val archunitVersion: String by project

dependencies {
    // Cip170MetadataFactory derives the label-170 digest via signify's CESR Diger.
    implementation("id.veridian:signify:$signifyVersion")
    // @DomainEvent on DocumentPublishCommand (moved here from document_vault, WS3 step 1).
    implementation("org.jmolecules:jmolecules-events")
    // The IPFS port + implementations live here so BOTH the publisher (which pins at
    // dispatch) and the vault (which needs a CID during an attestation ceremony) can use
    // them without either module depending on the other.
    implementation("com.github.ipfs:java-ipfs-http-client:$ipfsHttpClientVersion")
    testImplementation("com.tngtech.archunit:archunit-junit5:$archunitVersion")
}
