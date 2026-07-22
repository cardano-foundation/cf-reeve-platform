package org.cardanofoundation.lob.app.keri_attestation.service;

/**
 * R2 fix (Codex re-verification): fired by {@code KeriOobiService#lockAndUpsertLink}'s relink branch
 * once the identity-link write for the bump to {@code newBindingVersion} has been queued in the same
 * transaction — consumed only by {@link RelinkInvalidationSweepHandler}, an
 * {@code AFTER_COMMIT}-phase listener in this same package, so publishing it here never itself runs
 * the sweep synchronously (see that class's javadoc for why the sweep must wait for commit). Tiny and
 * package-private on purpose: this event never crosses a module boundary, so it carries nothing more
 * than the two fields the sweep query needs.
 */
record RelinkCompletedEvent(String userId, int newVersion) {
}
