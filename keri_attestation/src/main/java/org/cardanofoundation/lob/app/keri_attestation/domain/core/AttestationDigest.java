package org.cardanofoundation.lob.app.keri_attestation.domain.core;

/**
 * The digest an {@code AttestationTargetProvider} hands back from {@code prepareDigest} (design
 * §3.3/§4.6): {@code digestQb64} is the CESR Blake3-256 digest of the target's frozen metadata value
 * (the same digest the wallet is asked to anchor and that ends up as the on-chain {@code 170.d}),
 * {@code metadataLabel} is the Cardano metadata label the frozen value itself was published under
 * (e.g. {@code "1447"} for document_vault).
 */
public record AttestationDigest(String digestQb64, String metadataLabel) {
}
