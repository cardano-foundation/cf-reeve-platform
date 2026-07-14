package org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.metadata.helper.MetadataToJsonNoSchemaConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_common.service_assistance.JsonSchemaMetadataChecker;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentEntity;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

/**
 * Asserts the 1447 DOCUMENT manifest shape (spec: "Publishing — flow and formats"). The data section must
 * carry exactly the six fields below — nothing else — so any accidental addition of a PII-capable field is
 * caught here (spec B5 #3).
 */
class DocumentMetadataSerialiserTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2024-06-01T10:15:30Z"), ZoneId.of("UTC"));
    private static final long CREATION_SLOT = 123456L;

    private OrganisationPublicApi organisationPublicApi;
    private DocumentMetadataSerialiser serialiser;

    @BeforeEach
    void setUp() {
        organisationPublicApi = mock(OrganisationPublicApi.class);
        serialiser = new DocumentMetadataSerialiser(organisationPublicApi, FIXED_CLOCK);
    }

    private static DocumentEntity fixture() {
        DocumentEntity entity = new DocumentEntity();
        entity.setId("doc-1");
        entity.setOrganisationId("org-1");
        entity.setEnvelopeVersion(1);
        entity.setContentHash("a".repeat(64));
        entity.setPlaintextHash("b".repeat(64));
        entity.setPayloadNonce("c".repeat(24));
        entity.setCiphertextBase64("Y2lwaGVydGV4dA==");
        entity.setSlots(List.of(
                new DocumentEntity.Slot("d".repeat(64), "e".repeat(96)),
                new DocumentEntity.Slot("f".repeat(64), "0".repeat(96))));
        return entity;
    }

    private static Organisation organisationFixture() {
        return Organisation.builder()
                .id("org-1")
                .name("Acme")
                .taxIdNumber("TAX-1")
                .countryCode("CH")
                .accountPeriodDays(365)
                .currencyId("ISO_4217:CHF")
                .reportCurrencyId("ISO_4217:CHF")
                .build();
    }

    @Test
    void serialisesTheNormativeDocumentManifest() {
        when(organisationPublicApi.findByOrganisationId("org-1")).thenReturn(Optional.of(organisationFixture()));

        MetadataMap metadataMap = serialiser.serialiseToMetadataMap(fixture(), "bafy-cid-1", CREATION_SLOT);

        assertThat(metadataMap.get("type")).isEqualTo("DOCUMENT");
        assertThat(metadataMap.get("metadata")).isNotNull();
        assertThat(metadataMap.get("org")).isNotNull();

        MetadataMap org = (MetadataMap) metadataMap.get("org");
        assertThat(org.get("id")).isEqualTo("org-1");
        assertThat(org.get("name")).isEqualTo("Acme");

        MetadataMap metadata = (MetadataMap) metadataMap.get("metadata");
        assertThat(metadata.get("creation_slot")).isEqualTo(BigInteger.valueOf(CREATION_SLOT));
        assertThat(metadata.get("version")).isEqualTo(DocumentMetadataSerialiser.VERSION);

        MetadataMap data = (MetadataMap) metadataMap.get("data");
        assertThat(data.get("id")).isEqualTo("doc-1");
        assertThat(data.get("ipfs_cid")).isEqualTo("bafy-cid-1");
        assertThat(data.get("content_hash")).isEqualTo("a".repeat(64));
        assertThat(data.get("plaintext_hash")).isEqualTo("b".repeat(64));
        assertThat(data.get("envelope_version")).isEqualTo(BigInteger.valueOf(1));
        assertThat(data.get("slot_count")).isEqualTo(BigInteger.valueOf(2));

        // nothing else may be present in the data section (spec B5 #3 — no PII-capable field can sneak in)
        Set<String> dataKeys = ((List<?>) data.keys()).stream().map(Object::toString).collect(Collectors.toSet());
        assertThat(dataKeys).containsExactlyInAnyOrder(
                "id", "ipfs_cid", "content_hash", "plaintext_hash", "envelope_version", "slot_count");
    }

    @Test
    void unknownOrganisation_throwsInsteadOfPublishingWithoutOrgSection() {
        when(organisationPublicApi.findByOrganisationId("org-1")).thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> serialiser.serialiseToMetadataMap(fixture(), "bafy-cid-1", CREATION_SLOT));
    }

    /**
     * End-to-end guard that the serialiser output and the JSON schema agree: serialise a manifest exactly as
     * {@link DocumentL1TransactionCreator} does (CBOR -> JSON) and validate it against
     * {@code document_lob_blockchain_transaction_metadata_schema.json} with the production checker. Mirrors
     * {@code SpendingEventMetadataSerialiserTest#serialisedBundleValidatesAgainstSchema}.
     */
    @Test
    void serialisedManifestValidatesAgainstSchema() throws Exception {
        when(organisationPublicApi.findByOrganisationId("org-1")).thenReturn(Optional.of(organisationFixture()));

        MetadataMap metadataMap = serialiser.serialiseToMetadataMap(fixture(),
                "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi", CREATION_SLOT);

        byte[] bytes = CborSerializationUtil.serialize(metadataMap.getMap());
        String json = MetadataToJsonNoSchemaConverter.cborBytesToJson(bytes);

        JsonSchemaMetadataChecker checker = new JsonSchemaMetadataChecker(new ObjectMapper());
        checker.setMetadataSchemaResource(new ClassPathResource("document_lob_blockchain_transaction_metadata_schema.json"));
        checker.setEnableChecker(true);

        assertThat(checker.checkTransactionMetadata(json)).isTrue();
    }

    /**
     * Defense-in-depth: a manifest whose {@code data} section gains an extra field (e.g. an accidental PII leak)
     * must fail schema validation, since the schema declares exactly the six normative keys with
     * {@code additionalProperties: false}.
     */
    @Test
    void manifestWithExtraDataFieldFailsSchemaValidation() throws Exception {
        when(organisationPublicApi.findByOrganisationId("org-1")).thenReturn(Optional.of(organisationFixture()));

        MetadataMap metadataMap = serialiser.serialiseToMetadataMap(fixture(),
                "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi", CREATION_SLOT);

        byte[] bytes = CborSerializationUtil.serialize(metadataMap.getMap());
        String json = MetadataToJsonNoSchemaConverter.cborBytesToJson(bytes);

        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) objectMapper.readTree(json);
        ((ObjectNode) root.get("data")).put("unexpected_field", "should-not-be-here");
        String tamperedJson = objectMapper.writeValueAsString(root);

        JsonSchemaMetadataChecker checker = new JsonSchemaMetadataChecker(objectMapper);
        checker.setMetadataSchemaResource(new ClassPathResource("document_lob_blockchain_transaction_metadata_schema.json"));
        checker.setEnableChecker(true);

        assertThat(checker.checkTransactionMetadata(tamperedJson)).isFalse();
    }
}
