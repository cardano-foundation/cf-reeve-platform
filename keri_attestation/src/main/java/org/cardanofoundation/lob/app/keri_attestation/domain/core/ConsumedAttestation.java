package org.cardanofoundation.lob.app.keri_attestation.domain.core;

/**
 * The result of {@code AttestationConsumptionApi#validateAndConsume} (design §4.6) — everything a
 * target-owning module (e.g. document_vault) needs to bind an on-chain attestation to its own
 * record: the AID that attested (from the caller's {@code KeriIdentityLinkEntity}), the raw
 * metadata digest and the payload SAID the wallet actually anchored, and the KEL coordinates of the
 * anchoring event.
 *
 * <p><b>{@code digestQb64} vs {@code payloadSaid} (design §4.4 rev 3):</b> these are two distinct
 * values, kept separate on purpose. {@code digestQb64} is the raw CESR digest of the label-{@code
 * metadataLabel} metadata value (e.g. the 1447 document metadata) — used for freeze/digest matching
 * only ({@code DocumentAttestationLookup#loadForDispatch}). {@code payloadSaid} is the SAID of the
 * saidified remotesign request payload the wallet's KEL interaction event actually anchors as its
 * seal — this is the value that becomes the on-chain {@code 170.d}
 * ({@code DocumentAttestationLookup#attestMap}). Conflating the two was the direct-digest design
 * (rev 2) that a real Veridian wallet never accepted (no notification at all); do not merge them
 * back into one field.
 */
public record ConsumedAttestation(String ceremonyId, String aid, String digestQb64, String payloadSaid,
                                   String metadataLabel, String kelSequence) {
}
