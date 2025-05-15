val openCsvVersion: String by project

dependencies {

    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("com.opencsv:opencsv:$openCsvVersion")
    implementation(project(":support"))
}
