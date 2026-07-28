package org.cardanofoundation.lob.app.blockchain_common.domain.events;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

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
            if (component.getName().equals("recipientKeyHash")) {
                // sha256 of a PUBLIC key - publishable on-chain data, like organisationId above, and the
                // anchor the Indexer's recipient filter matches on. Exempted by name on purpose:
                // renaming the field to dodge the pattern would hide a real format decision behind a
                // euphemism. The guard still rejects recipientEmail, recipientLabel, recipientRef and
                // every other variant.
                continue;
            }
            assertFalse(FORBIDDEN.matcher(component.getName()).matches(),
                    "PII-looking field on the publish path: " + component.getName());
        }
    }
}
