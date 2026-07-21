package org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.document_vault.domain.events.DocumentPublishCommand;

/**
 * Converts a {@link DocumentPublishCommand} into a blockchain-publisher {@link DocumentEntity}, field-by-field,
 * ready to be stored in {@code STORED} state for later IPFS/L1 dispatch. PII-free by construction (spec B5 #3):
 * the command carries no e-mails, key ids, file names or account ids, so neither does the resulting entity.
 */
@Service
public class DocumentConverter {

    public DocumentEntity convertToDbDetached(DocumentPublishCommand command) {
        DocumentEntity entity = new DocumentEntity();
        entity.setId(command.documentId());
        entity.setOrganisationId(command.organisationId());
        entity.setEnvelopeVersion(command.envelopeVersion());
        entity.setContentHash(command.contentHash());
        entity.setPlaintextHash(command.plaintextHash());
        entity.setPayloadNonce(command.payloadNonce());
        entity.setCiphertextBase64(command.ciphertextBase64());
        entity.setSlots(convertSlots(command.slots()));
        entity.setAttestationCeremonyId(command.attestationCeremonyId());
        entity.setL1SubmissionData(Optional.of(L1SubmissionData.builder()
                .publishStatus(BlockchainPublishStatus.STORED)
                .build()));

        return entity;
    }

    private List<DocumentEntity.Slot> convertSlots(List<DocumentPublishCommand.PublishSlot> slots) {
        if (slots == null) {
            return List.of();
        }
        return slots.stream()
                .map(slot -> new DocumentEntity.Slot(slot.ephemeralPub(), slot.wrappedDek()))
                .collect(Collectors.toList());
    }

}
