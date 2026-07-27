package org.cardanofoundation.lob.app.document_vault.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.springframework.beans.factory.ObjectProvider;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_common.service_assistance.Cip170MetadataFactory;
import org.cardanofoundation.lob.app.document_vault.domain.card.KeyCardDto;
import org.cardanofoundation.lob.app.document_vault.domain.enums.CardSubjectType;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;

/**
 * The platform (B2) must recompute the SAME card-attestation digest the indexer anchored on-chain, or
 * import verification silently rejects genuine cards. This test cross-checks against the indexer's
 * golden vector (reeve-indexing-example {@code CardAttestationDigestFactoryTest}, same fixed card).
 */
class CardAttestationDigestFactoryTest {

    private final CardAttestationDigestFactory digestFactory = build();

    @SuppressWarnings("unchecked")
    private static CardAttestationDigestFactory build() {
        ObjectProvider<Cip170MetadataFactory> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(new Cip170MetadataFactory());
        return new CardAttestationDigestFactory(provider);
    }

    @Test
    void recomputesTheIndexersGoldenVectorDigest() {
        String digest = digestFactory.digestOf(goldenCard());
        // Pinned by the indexer's own golden-vector test for the SAME fixed card. If this fails, the
        // two digest formulas have diverged and no genuine attested card will verify.
        assertEquals("EGOZMuqWYxA-3YhsxIjiSGRtXBrMxtMDTrKNCmOt-7C9", digest);
        assertTrue(digest.startsWith("E"), "Blake3-256 CESR digests start with 'E'");
    }

    @Test
    void attestationBlockNeverAffectsTheDigest() {
        KeyCardDto withAttestation = goldenCard();
        withAttestation.setAttestation(new KeyCardDto.CardAttestation("http://oobi", "Eaid", "Ecred",
                "Eschema", "tx", "cesr"));
        assertEquals(digestFactory.digestOf(goldenCard()), digestFactory.digestOf(withAttestation));
    }

    @Test
    void changingACoveredFieldChangesTheDigest() {
        String base = digestFactory.digestOf(goldenCard());
        KeyCardDto other = goldenCard();
        other.setKey(new KeyCardDto.Key("a".repeat(64), "Bob's key", KeyAssurance.PORTABLE,
                "2026-01-01T00:00:00Z"));
        assertNotEquals(base, digestFactory.digestOf(other));
    }

    private static KeyCardDto goldenCard() {
        KeyCardDto card = new KeyCardDto();
        card.setV(1);
        card.setType("REEVE_KEY_CARD");
        card.setSubject(new KeyCardDto.Subject(CardSubjectType.REEVE_ACCOUNT, "kc-sub-1",
                "Bob Example", "bob@example.org", "75f95560"));
        card.setKey(new KeyCardDto.Key("8f".repeat(32), "Bob's key", KeyAssurance.PORTABLE,
                "2026-01-01T00:00:00Z"));
        return card;
    }
}
