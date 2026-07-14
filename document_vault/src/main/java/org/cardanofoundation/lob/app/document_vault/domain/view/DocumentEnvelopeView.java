package org.cardanofoundation.lob.app.document_vault.domain.view;

import java.time.LocalDateTime;
import java.util.List;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;

/**
 * Document detail (blueprint D2) — the ONLY view allowed to carry ciphertext (spec B5 #2;
 * enforced by ArchUnit in Task 13).
 *
 * Access is two-tier:
 *  - ANY member of the document's organisation gets the metadata and {@code recipients} (who can
 *    read this — derived from the slots' keys, carrying NO key material). The org-wide listing
 *    already reveals that the document exists to every org member, so hiding the detail behind a
 *    404 protected nothing and broke the detail page for the org.
 *  - ONLY the creator and the recipients get the ENVELOPE: {@code payload} AND {@code slots}. For
 *    everyone else both are null and {@code envelopeAccessible} is false.
 *
 * The slots are inside the participant gate deliberately. A wrappedDek is useless without the
 * matching private key, but it is still wrapped key material, and a DRAFT is not public — the
 * "it's on IPFS anyway" argument applies only to published documents, and may never apply at all.
 * Handing wrapped DEKs to org members who cannot use them buys nothing and would leave them
 * holding material that becomes interesting the day a recipient's key leaks.
 *
 * Slots keep keyId/recipientRef labels: this is an org-internal, authorized API — unlike the
 * public IPFS document, which strips them.
 */
public record DocumentEnvelopeView(String documentId,
                                   String organisationId,
                                   VaultDocumentStatus status,
                                   int envelopeVersion,
                                   String fileName,
                                   String contentType,
                                   String description,
                                   long sizeBytes,
                                   String contentHash,
                                   String plaintextHash,
                                   boolean envelopeAccessible,
                                   PayloadView payload,
                                   List<SlotView> slots,
                                   List<RecipientView> recipients,
                                   LedgerDispatchStatus ledgerDispatchStatus,
                                   String ledgerDispatchError,
                                   String txHash,
                                   String ipfsCid,
                                   String createdByName,
                                   LocalDateTime createdAt) {

    public record PayloadView(String ciphertext, String nonce) {
    }

    public record SlotView(String keyId, String recipientRef, String ephemeralPub, String wrappedDek) {
    }

    /** "Who can read this?" — derived from the slots' keys. Carries NO key material. */
    public record RecipientView(String keyId, String accountId, String displayName, String label,
                                KeyAssurance assurance) {
    }
}
