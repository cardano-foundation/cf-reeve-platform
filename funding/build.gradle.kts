val openCsvVersion: String by project

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.jmolecules:jmolecules-events")
    implementation("org.jmolecules:jmolecules-ddd")
    implementation("com.opencsv:opencsv:$openCsvVersion")
    implementation(project(":support"))
    implementation(project(":organisation"))
    implementation(project(":blockchain_common"))
}
