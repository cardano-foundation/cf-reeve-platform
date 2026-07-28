package org.cardanofoundation.lob.app.document_vault.domain.view;

import java.time.LocalDateTime;

import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;

/**
 * An addressbook contact, as managed at {@code /addressbook}. E-mail is org-internal contact data — it
 * never reaches IPFS or L1.
 *
 * @param entryId            the only unique thing about an entry. Names, e-mails and descriptions may
 *                           repeat; a public key may not, within one organisation.
 * @param assurance          null means UNKNOWN, and must render as such — a hand-entered contact makes
 *                           no claim about how its key was born, and the backend cannot check one.
 * @param homeOrganisationId the holder's own organisation as their card claimed it (e.g. "Privat"), or
 *                           null. Unverified provenance, shown so a sender can judge it.
 */
public record AddressbookEntryView(String entryId,
                                   String organisationId,
                                   String displayName,
                                   String email,
                                   String description,
                                   String publicKey,
                                   KeyAssurance assurance,
                                   String homeOrganisationId,
                                   LocalDateTime createdAt) {
}
