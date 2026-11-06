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
 * The platform must recompute the SAME card-attestation digest the issuing indexer wrapped into the
 * payload its wallet sealed, or import verification silently rejects genuine cards. This test
 * cross-checks against the indexer's golden vector (reeve-indexing-example
 * {@code CardAttestationDigestFactoryTest}, same fixed card) — a fixture written here from the
 * platform's own assumptions would agree with the platform and prove nothing.
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
                "Eschema", "3", "Eevent", "170", "Edigest", "Epayload", "cesr"));
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

    /**
     * The issuer omits these three when blank rather than sending empty strings, so a card that never
     * had them must digest identically to one whose values were cleared. Getting this wrong shifts the
     * digest and fails only against real cards, never against a fixture built the same wrong way.
     */
    @Test
    void blankOptionalFieldsDigestTheSameAsAbsentOnes() {
        assertEquals(digestFactory.digestOf(card(null, null, null)), digestFactory.digestOf(card("", "", "")));
    }

    @Test
    void eachOptionalFieldIsOmittedIndependently() {
        String allAbsent = digestFactory.digestOf(card(null, null, null));
        assertNotEquals(allAbsent, digestFactory.digestOf(card("Bob Example", null, null)));
        assertNotEquals(allAbsent, digestFactory.digestOf(card(null, "bob@example.org", null)));
        assertNotEquals(allAbsent, digestFactory.digestOf(card(null, null, "Bob's key")));
    }

    /**
     * The issuer always emits organisationId, using the empty string when the holder named none — it is
     * never omitted and never null. A card arriving without the field must therefore digest as "",
     * not as a missing key.
     */
    @Test
    void anAbsentOrganisationIdDigestsAsTheEmptyStringTheIssuerWouldHaveSent() {
        KeyCardDto absent = goldenCard();
        absent.setSubject(new KeyCardDto.Subject(CardSubjectType.REEVE_ACCOUNT, "kc-sub-1",
                "Bob Example", "bob@example.org", null));
        KeyCardDto empty = goldenCard();
        empty.setSubject(new KeyCardDto.Subject(CardSubjectType.REEVE_ACCOUNT, "kc-sub-1",
                "Bob Example", "bob@example.org", ""));

        assertEquals(digestFactory.digestOf(empty), digestFactory.digestOf(absent));
    }

    private static KeyCardDto card(String displayName, String email, String label) {
        KeyCardDto card = new KeyCardDto();
        card.setV(1);
        card.setType("REEVE_KEY_CARD");
        card.setSubject(new KeyCardDto.Subject(CardSubjectType.EXTERNAL, "indexer-uuid-1",
                displayName, email, ""));
        card.setKey(new KeyCardDto.Key("8f".repeat(32), label, KeyAssurance.PORTABLE,
                "2026-01-01T00:00:00Z"));
        return card;
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
