package org.cardanofoundation.lob.app.document_vault.domain.view;

import java.time.LocalDateTime;

import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;

public record VaultKeyView(String keyId,
                           String organisationId,
                           String label,
                           String publicKey,
                           String email,
                           String credentialId,
                           KeyAssurance assurance,
                           KeyOrigin origin,
                           String issuerId,
                           /** False once this key's issuer is de-trusted: still yours, still able to
                            *  decrypt what you already received, but no longer an encryption target
                            *  for anyone (contract §2.8.5). Null issuer (self-enrolled) ⇒ always true. */
                           boolean issuerTrusted,
                           boolean external,
                           LocalDateTime createdAt) {
}
