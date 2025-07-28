import info.solidsoft.gradle.pitest.PitestTask
import org.gradle.api.JavaVersion.VERSION_21
import org.jreleaser.model.Active
import org.jreleaser.model.Signing.Mode

plugins {
    java
    id("io.spring.dependency-management") version "1.1.5"
    id("com.github.ben-manes.versions") version "0.51.0"
    id("info.solidsoft.pitest") version "1.15.0"
    id("com.diffplug.spotless") version "6.19.0"
    id("maven-publish")
    id("jacoco")
    id("org.sonarqube") version "6.1.0.5360"
    id("org.cyclonedx.bom") version "2.2.0"
    id("org.jreleaser") version "1.19.0"
}

jreleaser {
    signing {
        active = Active.ALWAYS
        mode = Mode.COMMAND
    }
    deploy {
        maven {
            mavenCentral {
                create("release-deploy") {
                    active = (Active.ALWAYS)
                    url = "https://central.sonatype.com/api/v1/publisher"
                    sign = true
                    sourceJar = true
                    javadocJar = true
                    snapshotSupported = true
                    verifyPom = true
                    stagingRepository("build/staging-deploy")
                }
            }
        }
    }
}

allprojects {
    repositories {
        mavenLocal()
        mavenCentral()
        maven {
            name = "sonatypeSnapshots"
            url = uri("https://oss.sonatype.org/content/repositories/snapshots")
        }
        maven {
            name = "local"
            url = uri("file://${project.layout.buildDirectory}/repo")
        }
    }


}

subprojects {
    apply(plugin = "java")
    apply(plugin = "java-library")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "com.github.ben-manes.versions")
    apply(plugin = "info.solidsoft.pitest")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "maven-publish")
    apply(plugin = "jacoco")
    apply(plugin = "org.sonarqube")

    sourceSets {
        named("main") {
            java {
                setSrcDirs(listOf("src/main/java"))
            }
            resources {
                setSrcDirs(listOf("src/main/resources"))
            }
        }
    }

    repositories {
        //mavenLocal()
        mavenCentral()
        maven {
            name = "sonatypeSnapshots"
            url = uri("https://oss.sonatype.org/content/repositories/snapshots")
        }
        maven {
            name = "local"
            url = uri("file://${project.layout.buildDirectory}/repo")
        }
    }

    java {
        sourceCompatibility = VERSION_21
        withJavadocJar()
        withSourcesJar()
    }

    configurations {
        compileOnly {
            extendsFrom(configurations.annotationProcessor.get())
        }
    }

    extra["springBootVersion"] = "3.3.3"
    extra["springCloudVersion"] = "2023.0.0"
    extra["jMoleculesVersion"] = "2023.1.0"

    dependencies {
        implementation("org.springframework.data:spring-data-envers")

        implementation("org.flywaydb:flyway-core")
        implementation("org.flywaydb:flyway-database-postgresql")

        // needed to store json via JPA in PostgreSQL for
        // Hibernate 6.6, 6.5, 6.4, and 6.3
        implementation("io.hypersistence:hypersistence-utils-hibernate-63:3.8.3")

        runtimeOnly("io.micrometer:micrometer-registry-prometheus")
        runtimeOnly("org.postgresql:postgresql")

        annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
        annotationProcessor("org.springframework.boot:spring-boot-actuator-autoconfigure")
        implementation("org.springframework.boot:spring-boot-starter-actuator")
        implementation("org.springframework.boot:spring-boot-starter-cache")

        testImplementation("org.springframework.boot:spring-boot-starter-test")
        testImplementation("org.springframework.boot:spring-boot-testcontainers")

        testImplementation("org.testcontainers:junit-jupiter")
        testImplementation("org.testcontainers:postgresql")

        runtimeOnly("org.springframework.boot:spring-boot-properties-migrator")
        implementation("org.zalando:problem-spring-web-starter:0.29.1")
        implementation("io.vavr:vavr:0.10.4")
        implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

        implementation("org.scribe:scribe:1.3.7") // needed for OAuth 1.0 for NetSuite Module

        implementation("javax.xml.bind", "jaxb-api", "2.3.0")
        implementation("org.glassfish.jaxb:jaxb-runtime:2.3.2") // needed for OAuth 1.0 for NetSuite Module

        implementation("com.networknt:json-schema-validator:1.5.1")

        implementation("com.google.guava:guava:33.3.0-jre")

        implementation("org.apache.commons:commons-collections4:4.4")
        implementation("org.javers:javers-core:7.6.1")

        implementation("com.bloxbean.cardano:cardano-client-crypto:0.6.0")
        implementation("com.bloxbean.cardano:cardano-client-backend-blockfrost:0.6.0")
        implementation("com.bloxbean.cardano:cardano-client-quicktx:0.6.0")

        implementation("org.mapstruct:mapstruct:1.5.5.Final")
        annotationProcessor("org.mapstruct:mapstruct-processor:1.5.5.Final")
        annotationProcessor("org.projectlombok:lombok-mapstruct-binding:0.2.0")

        compileOnly("org.projectlombok:lombok:1.18.32")
        annotationProcessor("org.projectlombok:lombok:1.18.32")
        implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.5.0")
        testCompileOnly("org.projectlombok:lombok:1.18.32")
        testAnnotationProcessor("org.projectlombok:lombok:1.18.32")
        testImplementation("io.rest-assured:rest-assured:5.5.0")
        testImplementation("org.wiremock:wiremock-standalone:3.6.0")
        testImplementation("net.jqwik:jqwik:1.9.0") // Jqwik for property-based testing
        testImplementation("org.assertj:assertj-core:3.26.0")
        testImplementation("org.pitest:pitest-junit5-plugin:1.2.1")
    }

    dependencyManagement {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:${property("springBootVersion")}")
            mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
            mavenBom("org.jmolecules:jmolecules-bom:${property("jMoleculesVersion")}")
        }
    }

    tasks {
//        val ENABLE_PREVIEW = "--enable-preview"

        withType<JavaCompile> {
            options.encoding = "UTF-8"
//            options.compilerArgs.add(ENABLE_PREVIEW)
            //options.compilerArgs.add("-Xlint:preview")
        }

        withType<Jar> {
            archiveBaseName.set("lob-platform-${project.name}")
        }

        withType<Test> {
            useJUnitPlatform()
//            jvmArgs(ENABLE_PREVIEW)
            finalizedBy("jacocoTestReport")
        }

        withType<PitestTask> {
//            jvmArgs(ENABLE_PREVIEW)
        }

        withType<JavaExec> {
//            jvmArgs(ENABLE_PREVIEW)
        }

        withType<JacocoReport> {
            reports {
                xml.required.set(true) // XML report for SonarCloud
                html.required.set(true) // HTML report for local use
            }

//            sourceDirectories.setFrom(files("src/main/java"))
            executionData.setFrom(fileTree(layout.buildDirectory).include("jacoco/test.exec"))
        }
    }

    sonar {
        properties {
            property("sonar.java.enablePreview", "false")

            property("sonar.exclusions", "" +
                    "**/views/**, " +
                    "**/requests/**, " +
                    "**/entity/**, " +
                    "**/config/**, " +
                    "**/domain/**, " +
                    "**/repository/**, " +
                    "**/spring_web/**," +
                    "**/spring_audit/**")
        }
    }

    spotless {
        java {
            target("**/src/**/*.java")

            // Exclude target directory
            targetExclude("**/target/**/*.java")

            // Remove wildcard imports
            removeUnusedImports()

            // Define the import order
            importOrder("java", "jakarta", "javax", "lombok", "org.springframework", "", "org.junit", "org.cardanofoundation", "#")

            // Trim trailing whitespace
            trimTrailingWhitespace()

            // Set indentation: 2 spaces per tab
            indentWithSpaces(2)

            // Ensure files end with a newline
            endWithNewline()
        }
    }

    pitest {
        //adds dependency to org.pitest:pitest-junit5-plugin and sets "testPlugin" to "junit5"
        jvmArgs.add("--enable-preview")
        //targetClasses.set(setOf("org.cardanofoundation.lob.app.*"))
        //targetTests.set(setOf("org.cardanofoundation.lob.app.accounting_reporting_core.*"))
        exportLineCoverage = true
        timestampedReports = false
        threads = 2
        //junit5PluginVersion.set("1.2.2")
        outputFormats.set(listOf("HTML"))
        mutators.set(listOf("DEFAULTS"))
        //mutationThreshold.set(20)
    }

    val isSnapshot = (version as String).contains("SNAPSHOT")

    publishing {
        publications {
            create<MavenPublication>("mavenJava") {
                from(components["java"])
                artifactId = "cf-lob-platform-" + project.name
                pom {
                    name = project.name
                    description = "Reeve, also known as Ledger on the Blockchain (LOB), empowers organizations to securely record and share critical financial data on the Cardano blockchain, ensuring its integrity and verifiability for all stakeholders."
                    url = "https://github.com/cardano-foundation/cf-reeve-platform/"
                    licenses {
                        license {
                            name = "Apache License 2.0"
                            url = "https://www.apache.org/licenses/LICENSE-2.0"
                        }
                    }
                    developers {
                        developer {
                            id = "CF"
                            name = "CF"
                        }
                    }
                    scm {
                        connection = "scm:git:git://github.com/cardano-foundation/cf-reeve-platform/"
                        developerConnection = "scm:git:ssh://git@github.com:cardano-foundation/cf-reeve-platform.git"
                        url = "https://github.com/cardanofoundation/cf-signify-java"
                    }
                }
            }
        }

        repositories {
            // Aggregate release in local build directory which jrelease uses to deploy to Maven Central.
            maven {
                name = "build"
                url = uri(File(rootProject.projectDir.path, "build/staging-deploy"))
            }
            maven {
                name = "localM2"
                url = uri("${System.getProperty("user.home")}/.m2/repository")
            }
            maven {
                name = "gitlabPrivate"
                url = uri(System.getenv("GITLAB_MAVEN_REGISTRY_URL") ?: "")
                credentials(HttpHeaderCredentials::class) {
                    name = "PRIVATE-TOKEN"
                    value = System.getenv("GITLAB_MAVEN_REGISTRY_PASS") ?: ""
                }
                authentication {
                    val header by registering(HttpHeaderAuthentication::class)
                }
            }
        }
    }

}
