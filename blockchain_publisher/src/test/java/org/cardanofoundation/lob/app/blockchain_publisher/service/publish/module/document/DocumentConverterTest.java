package org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_common.domain.events.DocumentPublishCommand;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentEntity;

/**
 * {@link DocumentConverter#convertToDbDetached} must carry {@code attestationCeremonyId} from the
 * command into the publisher-side {@link DocumentEntity} column (design §5.1, Task 14) — this is
 * the mapping {@code BlockchainPublisherService#storeDocumentForDispatchLater} relies on so a
 * document dispatched via an attested publish keeps its ceremony binding. {@link
 * DocumentConverter#toPublishCommand} is the reverse mapping (WS3 step 1) that lets
 * {@code DocumentL1TransactionCreator} feed a persisted entity back into the {@code blockchain_common}
 * serialisers, which take a {@link DocumentPublishCommand}, not an entity - it must round-trip every
 * field losslessly for the two dispatch/attest paths to stay byte-identical.
 */
class DocumentConverterTest {

    private final DocumentConverter converter = new DocumentConverter();

    private static DocumentPublishCommand command(String attestationCeremonyId) {
        return new DocumentPublishCommand(
                "org-1",
                "doc-1",
                1,
                "a".repeat(64),
                "b".repeat(64),
                "c".repeat(24),
                "ZGF0YQ==",
                List.of(new DocumentPublishCommand.PublishSlot("d".repeat(64), "e".repeat(96), "300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae")),
                attestationCeremonyId,
                attestationCeremonyId == null ? null
                        : new DocumentPublishCommand.ConsumedAttestationRef("Eaid-1", "Epayloadsaid-1", "5"));
    }

    @Test
    void convertToDbDetachedCarriesTheAttestationCeremonyIdWhenPresent() {
        DocumentEntity entity = converter.convertToDbDetached(command("cer-1"));

        assertThat(entity.getAttestationCeremonyId()).isEqualTo("cer-1");
    }

    @Test
    void convertToDbDetachedLeavesAttestationCeremonyIdNullForAPlainPublish() {
        DocumentEntity entity = converter.convertToDbDetached(command(null));

        assertThat(entity.getAttestationCeremonyId()).isNull();
    }

    @Test
    void toPublishCommandReversesConvertToDbDetachedLosslessly() {
        DocumentPublishCommand original = command("cer-1");

        DocumentEntity entity = converter.convertToDbDetached(original);
        DocumentPublishCommand roundTripped = converter.toPublishCommand(entity);

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void toPublishCommandRoundTripsAPlainPublishWithNoCeremonyId() {
        DocumentPublishCommand original = command(null);

        DocumentEntity entity = converter.convertToDbDetached(original);
        DocumentPublishCommand roundTripped = converter.toPublishCommand(entity);

        assertThat(roundTripped).isEqualTo(original);
    }

}
