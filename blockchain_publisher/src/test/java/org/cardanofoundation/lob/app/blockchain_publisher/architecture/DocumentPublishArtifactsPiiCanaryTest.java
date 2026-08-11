package org.cardanofoundation.lob.app.blockchain_publisher.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_common.domain.events.DocumentPublishCommand;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.DocumentIpfsSerialiser;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.DocumentMetadataSerialiser;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

class DocumentPublishArtifactsPiiCanaryTest {

    private static final List<String> CANARIES = List.of(
            "canary-mail@example.org", "canary-recipient-label", "very-secret-filename");

    @Test
    void neitherIpfsDocumentNorL1MetadataCanCarryPii() throws Exception {
        // toJson() declares a checked CborException — thrown up rather than caught (brief's snippet omits the
        // throws clause, which does not compile; this is the minimal fix, no assertion semantics changed)
        DocumentPublishCommand command = new DocumentPublishCommand(
                "org-1",
                "doc-1",
                1,
                "a".repeat(64),
                "b".repeat(64),
                "c".repeat(24),
                "Y2lwaGVydGV4dA==",
                List.of(new DocumentPublishCommand.PublishSlot("d".repeat(64), "e".repeat(96), "300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae")),
                null, null);

        String ipfsJson = new DocumentIpfsSerialiser(new ObjectMapper()).serialise(command);

        // The org admin e-mail exists server-side, on the resolved organisation entity...
        Organisation organisationEntity = Organisation.builder()
                .id("org-1").name("Org").taxIdNumber("TAX").countryCode("CH")
                .accountPeriodDays(365).currencyId("ISO_4217:CHF").reportCurrencyId("ISO_4217:CHF")
                .phoneNumber("x").city("x").postCode("x").province("x").address("x")
                .adminEmail("canary-mail@example.org")
                .build();
        // ...but blockchain_common must not depend on organisation, so callers (DocumentL1TransactionCreator,
        // DocumentAttestationTargetProvider) resolve the organisation and extract only the five 1447
        // org-section fields below — admin e-mail is never passed to the serialiser at all. Mirrored here via
        // the same value-object extraction those callers use. (Local variable deliberately not named "org" -
        // that would shadow the "org.cardanofoundation..." package prefix used inline below.)
        org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation publisherOrg =
                org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation.fromOrganisationEntity(organisationEntity);

        // CBORMetadataMap does NOT override toString() — toJson() is the scannable serialised form
        String metadata = new DocumentMetadataSerialiser()
                .serialiseToMetadataMap(command, "bafy-1",
                        publisherOrg.getId(), publisherOrg.getName(), publisherOrg.getTaxIdNumber(),
                        publisherOrg.getCurrencyId(), publisherOrg.getCountryCode())
                .toJson();

        for (String canary : CANARIES) {
            assertFalse(ipfsJson.contains(canary), "PII canary in IPFS document: " + canary);
            assertFalse(metadata.contains(canary), "PII canary in L1 metadata: " + canary);
        }
    }
}
