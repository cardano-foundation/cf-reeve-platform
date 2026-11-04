package org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.blockchain_common.domain.events.DocumentPublishCommand;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;

/**
 * Converts between a {@link DocumentPublishCommand} and a {@link DocumentEntity} in both directions:
 * {@link #convertToDbDetached} builds the entity stored for later dispatch, {@link #toPublishCommand}
 * rebuilds the command the {@code blockchain_common} serialisers expect. PII-free by construction,
 * since the command carries none and the round trip introduces none.
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
        if (command.attestation() != null) {
            entity.setAttestationAid(command.attestation().aid());
            entity.setAttestationPayloadSaid(command.attestation().payloadSaid());
            entity.setAttestationKelSequence(command.attestation().kelSequence());
        }
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
                .map(slot -> new DocumentEntity.Slot(slot.ephemeralPub(), slot.wrappedDek(), slot.recipientKeyHash()))
                .collect(Collectors.toList());
    }

    /**
     * Reverses {@link #convertToDbDetached}. {@code ipfsCid} and {@code l1SubmissionData} have no
     * command counterpart: the former is a separate serialiser parameter, the latter is dispatch
     * bookkeeping the command never carried.
     */
    public DocumentPublishCommand toPublishCommand(DocumentEntity entity) {
        return new DocumentPublishCommand(
                entity.getOrganisationId(),
                entity.getId(),
                entity.getEnvelopeVersion(),
                entity.getContentHash(),
                entity.getPlaintextHash(),
                entity.getPayloadNonce(),
                entity.getCiphertextBase64(),
                convertSlotsToCommand(entity.getSlots()),
                entity.getAttestationCeremonyId(),
                entity.getAttestationCeremonyId() == null ? null
                        : new DocumentPublishCommand.ConsumedAttestationRef(
                                entity.getAttestationAid(),
                                entity.getAttestationPayloadSaid(),
                                entity.getAttestationKelSequence()));
    }

    private List<DocumentPublishCommand.PublishSlot> convertSlotsToCommand(List<DocumentEntity.Slot> slots) {
        if (slots == null) {
            return List.of();
        }
        return slots.stream()
                .map(slot -> new DocumentPublishCommand.PublishSlot(slot.getEphemeralPub(), slot.getWrappedDek(),
                        slot.getRecipientKeyHash()))
                .collect(Collectors.toList());
    }

}
