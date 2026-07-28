dependencies {
    // Cip170MetadataFactory derives the label-170 digest via signify's CESR Diger.
    implementation("org.cardanofoundation:signify:0.1.2-PR62-d6aea58")
    // @DomainEvent on DocumentPublishCommand (moved here from document_vault, WS3 step 1).
    implementation("org.jmolecules:jmolecules-events")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}
