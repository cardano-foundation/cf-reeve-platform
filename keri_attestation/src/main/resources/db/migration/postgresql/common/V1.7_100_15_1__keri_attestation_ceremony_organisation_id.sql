-- The target's organisation, resolved from the AttestationTargetProvider when the ceremony is created.
--
-- AUTH_BEGIN is no longer submitted by this module: it is handed to blockchain_publisher, whose
-- dispatcher iterates organisations, so a ceremony that cannot name one could never have its
-- AUTH_BEGIN transaction picked up. Nullable because ceremonies created before this column existed
-- carry no organisation; those can still complete every step except a fresh AUTH_BEGIN submission.
ALTER TABLE keri_attestation_ceremony
    ADD COLUMN organisation_id VARCHAR(255);
