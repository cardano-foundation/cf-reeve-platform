package org.cardanofoundation.lob.app.keri_attestation.domain.core;

/**
 * The digest an {@code AttestationTargetProvider} hands back from {@code prepareDigest} (design
 * §3.3/§4.6): {@code digestQb64} is the CESR Blake3-256 digest of the target's frozen metadata value
 * — the digest the wallet is asked to anchor in its KEL. {@code metadataLabel} is the Cardano
 * metadata label the frozen value itself was published under (e.g. {@code "1447"} for
 * document_vault).
 *
 * <p><strong>This value is NOT what lands in the on-chain {@code 170.d}.</strong> Dispatch builds
 * {@code 170.d} from {@code ConsumedAttestation#payloadSaid()} (see
 * {@code DocumentAttestationLookup}), not from {@code digestQb64}. Publishing this digest directly
 * was considered and rejected; the javadoc previously claimed otherwise, which invited the rejected
 * design back in.
 */
public record AttestationDigest(String digestQb64, String metadataLabel) {
}
