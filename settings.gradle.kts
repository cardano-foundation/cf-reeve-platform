pluginManagement {
 repositories {
  gradlePluginPortal()
  mavenCentral()
 }
}

rootProject.name = "cf-lob-platform"

include (
 ":support",
 ":accounting_reporting_core",
 ":blockchain_publisher",
 ":blockchain_reader",
 ":blockchain_common",
 ":document_vault",
 ":keri_attestation",
 ":netsuite_altavia_erp_adapter",
 ":csv_erp_adapter",
 ":notification_gateway",
 ":organisation",
 ":reporting",
 ":funding",
 // Deployable application (not published to Maven Central)
 ":cf_netsuite_altavia_erp_connector",
 ":cf-application"
)
