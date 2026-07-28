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
 * Converts between a {@link DocumentPublishCommand} and a blockchain-publisher {@link DocumentEntity},
 * field-by-field, in both directions. {@link #convertToDbDetached} builds the entity stored in
 * {@code STORED} state for later IPFS/L1 dispatch; {@link #toPublishCommand} reverses that mapping so
 * a persisted entity loaded back for dispatch can be fed into the tier-neutral
 * {@code blockchain_common} serialisers ({@code DocumentIpfsSerialiser}, {@code
 * DocumentMetadataSerialiser}), which take a {@link DocumentPublishCommand}, not an entity. PII-free
 * by construction (spec B5 #3): the command carries no e-mails, key ids, file names or account ids, so
 * neither does the resulting entity, and the round trip introduces none either.
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

    /**
     * Reverses {@link #convertToDbDetached}: rebuilds the {@link DocumentPublishCommand} a persisted
     * {@link DocumentEntity} (loaded back for dispatch) was originally derived from, so
     * {@link DocumentL1TransactionCreator} can feed it into the {@code blockchain_common} serialisers.
     * {@code ipfsCid} and {@code l1SubmissionData} deliberately have no command counterpart - the
     * former is a separate parameter both serialisers already take, the latter is dispatch-status
     * bookkeeping the command never carried in the first place.
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
                entity.getAttestationCeremonyId());
    }

    private List<DocumentPublishCommand.PublishSlot> convertSlotsToCommand(List<DocumentEntity.Slot> slots) {
        if (slots == null) {
            return List.of();
        }
        return slots.stream()
                .map(slot -> new DocumentPublishCommand.PublishSlot(slot.getEphemeralPub(), slot.getWrappedDek()))
                .collect(Collectors.toList());
    }

}
