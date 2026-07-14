package org.cardanofoundation.lob.app.blockchain_publisher.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.document_vault.domain.events.DocumentPublishCommand;

/** Spec B5 #3: everything on the IPFS/L1 export path must be PII-free. */
class DocumentPublishCommandPiiTest {

    private static final Pattern FORBIDDEN =
            Pattern.compile("(?i).*(e?mail|recipient|account|label|file_?name|description|display).*");

    @Test
    void documentPublishCommandCarriesNoPiiFields() {
        for (var component : DocumentPublishCommand.class.getRecordComponents()) {
            if (component.getName().equals("organisationId")) {
                continue; // org id is public on-chain data, not PII
            }
            assertFalse(FORBIDDEN.matcher(component.getName()).matches(),
                    "PII-looking field on the publish path: " + component.getName());
        }
        for (var component : DocumentPublishCommand.PublishSlot.class.getRecordComponents()) {
            assertFalse(FORBIDDEN.matcher(component.getName()).matches());
        }
    }
}
