package org.cardanofoundation.lob.app.document_vault.domain.view;

import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;
import org.cardanofoundation.lob.app.document_vault.domain.enums.RecipientKind;

/**
 * Somebody you can encrypt to: either a colleague with a Reeve account, or an addressbook contact. One
 * record for both, because the picker shows one list. E-mail is org-internal contact data — it never
 * reaches IPFS or L1.
 *
 * {@code assurance} must be rendered in the recipient picker: encrypting to a
 * PORTABLE key is a weaker promise than encrypting to a PASSKEY key, and null is weaker still — it means
 * nobody ever claimed anything. `homeOrganisationId` should sit beside it. Trust here is the sender's
 * out-of-band judgement, and these are what they judge with.
 *
 * @param kind               ORG_KEY or ADDRESSBOOK_ENTRY. Decides which of the nullable fields mean
 *                           anything, and whether {@code recipientId} names an account or an entry.
 * @param recipientId        pass this back to {@code /recipients/resolve} — as a recipientAccountId for
 *                           ORG_KEY, or a recipientEntryId for ADDRESSBOOK_ENTRY.
 * @param keyId              the key/entry id to put in a document slot.
 * @param email              null for an ORG_KEY (the Keycloak account is the contact) and optional for a
 *                           hand-entered contact.
 * @param origin             null for an ADDRESSBOOK_ENTRY: a contact never enrolled anything here.
 * @param assurance          null means UNKNOWN, which is the honest answer for a hand-entered contact.
 * @param homeOrganisationId null for an ORG_KEY, whose organisation is simply yours.
 */
public record RecipientKeyView(RecipientKind kind,
                               String recipientId,
                               String keyId,
                               String displayName,
                               String email,
                               String publicKey,
                               String label,
                               KeyAssurance assurance,
                               KeyOrigin origin,
                               String homeOrganisationId) {
}
