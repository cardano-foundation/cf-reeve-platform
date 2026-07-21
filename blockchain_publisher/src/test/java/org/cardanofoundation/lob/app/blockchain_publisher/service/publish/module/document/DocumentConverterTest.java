package org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.events.DocumentPublishCommand;

/**
 * {@link DocumentConverter#convertToDbDetached} must carry {@code attestationCeremonyId} from the
 * command into the publisher-side {@link DocumentEntity} column (design §5.1, Task 14) — this is
 * the mapping {@code BlockchainPublisherService#storeDocumentForDispatchLater} relies on so a
 * document dispatched via an attested publish keeps its ceremony binding.
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
                List.of(new DocumentPublishCommand.PublishSlot("d".repeat(64), "e".repeat(96))),
                attestationCeremonyId);
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

}
