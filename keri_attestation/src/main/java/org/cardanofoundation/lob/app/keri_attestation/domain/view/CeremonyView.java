package org.cardanofoundation.lob.app.keri_attestation.domain.view;

import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;

/**
 * Client-facing snapshot of a ceremony (design §4.2/§4.6). {@code authBeginTxHash} is read from the
 * caller's {@code KeriIdentityLinkEntity} (it is an identity-level fact, not a ceremony-level one) —
 * everything else comes straight off the {@code KeriAttestationCeremonyEntity} row.
 */
public record CeremonyView(String id, CeremonyState state, RequiredSteps requiredSteps,
                            String errorTitle, String errorDetail,
                            String metadataDigest, String kelSequence, String kelEventSaid,
                            String authBeginTxHash) {
}
