package org.cardanofoundation.lob.app.keri_attestation.domain.core;

/**
 * The result of {@code AttestationConsumptionApi#validateAndConsume} (design §4.6) — everything a
 * target-owning module (e.g. document_vault) needs to bind an on-chain attestation to its own
 * record: the AID that attested (from the caller's {@code KeriIdentityLinkEntity}), the digest that
 * was anchored, and the KEL coordinates of the anchoring event.
 */
public record ConsumedAttestation(String ceremonyId, String aid, String digestQb64,
                                   String metadataLabel, String kelSequence) {
}
