package org.cardanofoundation.lob.app.keri_attestation.domain.view;

/**
 * Which one-time identity-level steps are still outstanding for a ceremony (design §4.2). Derived
 * from the ceremony's current state, not stored — a step is required exactly while the ceremony's
 * state precedes the state that step's completion produces.
 */
public record RequiredSteps(boolean oobi, boolean credential, boolean authBegin) {
}
