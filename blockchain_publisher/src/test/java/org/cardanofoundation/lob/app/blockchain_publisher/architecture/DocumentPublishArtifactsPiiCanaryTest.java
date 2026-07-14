package org.cardanofoundation.lob.app.blockchain_publisher.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.Mockito;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document.DocumentIpfsSerialiser;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document.DocumentMetadataSerialiser;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

class DocumentPublishArtifactsPiiCanaryTest {

    private static final List<String> CANARIES = List.of(
            "canary-mail@example.org", "canary-recipient-label", "very-secret-filename");

    @Test
    void neitherIpfsDocumentNorL1MetadataCanCarryPii() throws Exception {
        // toJson() declares a checked CborException — thrown up rather than caught (brief's snippet omits the
        // throws clause, which does not compile; this is the minimal fix, no assertion semantics changed)
        DocumentEntity entity = new DocumentEntity();
        entity.setId("doc-1");
        entity.setOrganisationId("org-1");
        entity.setEnvelopeVersion(1);
        entity.setContentHash("a".repeat(64));
        entity.setPlaintextHash("b".repeat(64));
        entity.setPayloadNonce("c".repeat(24));
        entity.setCiphertextBase64("Y2lwaGVydGV4dA==");
        entity.setSlots(List.of(new DocumentEntity.Slot("d".repeat(64), "e".repeat(96))));

        String ipfsJson = new DocumentIpfsSerialiser(new ObjectMapper()).serialise(entity);

        OrganisationPublicApi organisationPublicApi = Mockito.mock(OrganisationPublicApi.class);
        Mockito.when(organisationPublicApi.findByOrganisationId("org-1")).thenReturn(Optional.of(Organisation.builder()
                .id("org-1").name("Org").taxIdNumber("TAX").countryCode("CH")
                .accountPeriodDays(365).currencyId("ISO_4217:CHF").reportCurrencyId("ISO_4217:CHF")
                .phoneNumber("x").city("x").postCode("x").province("x").address("x")
                .adminEmail("canary-mail@example.org") // the org admin e-mail exists server-side...
                .build()));
        // CBORMetadataMap does NOT override toString() — toJson() is the scannable serialised form
        String metadata = new DocumentMetadataSerialiser(organisationPublicApi, Clock.systemUTC())
                .serialiseToMetadataMap(entity, "bafy-1", 1L)
                .toJson();

        for (String canary : CANARIES) {
            assertFalse(ipfsJson.contains(canary), "PII canary in IPFS document: " + canary);
            assertFalse(metadata.contains(canary), "PII canary in L1 metadata: " + canary);
        }
    }
}
