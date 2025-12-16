/// usr/bin/env jbang "$0" "$@" ; exit $?
///
// @formatter:off
//JAVA 24+
//COMPILE_OPTIONS -source 24
//RUNTIME_OPTIONS

//REPOS snapshots=https://central.sonatype.com/repository/maven-snapshots/
//REPOS central=https://repo.maven.apache.org/maven2
//DEPS org.cardanofoundation:signify:0.1.2-ebfb904-SNAPSHOT
//SOURCES KeriUtils.java
// @formatter:on

import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Coring;

public class CreateIdentifier {

    private static final String KERI_URL = "https://keria.staging.cardano-foundation.app.reeve.technology";
    private static final String KERI_BOOT_URL = "https://keria-boot.staging.cardano-foundation.app.reeve.technology";
    private static final String CLIENT_NAME = "GTReeveClient";

    public static void main(String[] args) throws Exception {
        String passcode = Coring.randomPasscode();

        SignifyClient client = KeriUtils.getOrCreateClient(KERI_URL, KERI_BOOT_URL, passcode);
        KeriUtils.Aid aid = KeriUtils.createAid(client, CLIENT_NAME);

        System.out.println("=== Identifier Created Successfully ===");
        System.out.println("Identifier Prefix (AID): " + aid.prefix());
        System.out.println("OOBI: " + aid.oobi());
        System.out.println("Passcode: " + passcode);
        System.out.println();
        System.out.println("Save these credentials - you'll need them to access this identifier!");
    }
}
