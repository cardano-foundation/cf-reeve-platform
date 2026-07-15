package org.cardanofoundation.lob.app.document_vault.domain.view;

import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;

/**
 * Addressbook entry. E-mail is org-internal contact data — it never reaches IPFS or L1 (spec B5 #3).
 *
 * `assurance` MUST be rendered in the recipient picker (blueprint I2 as amended): encrypting to a
 * PORTABLE key is a weaker promise than encrypting to a PASSKEY key, and the sender is entitled to
 * know which one they are choosing.
 */
public record RecipientKeyView(String accountId,
                               String displayName,
                               String email,
                               String keyId,
                               String publicKey,
                               String label,
                               KeyAssurance assurance,
                               KeyOrigin origin,
                               boolean external) {
}
