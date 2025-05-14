val jsonWebTokenVersion: String by project
val openCsvVersion: String by project

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("io.jsonwebtoken:jjwt-impl:$jsonWebTokenVersion")
    implementation("io.jsonwebtoken:jjwt-api:$jsonWebTokenVersion")
    implementation("io.jsonwebtoken:jjwt-jackson:$jsonWebTokenVersion")
    implementation("com.opencsv:opencsv:$openCsvVersion")

    implementation(project(":accounting_reporting_core"))
    implementation(project(":organisation"))
    implementation(project(":support"))
}
