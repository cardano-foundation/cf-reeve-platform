package org.cardanofoundation.lob.app.blockchain_publisher.service.keri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import io.vavr.control.Either;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentAttestationFreezeEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.DocumentAttestationFreezeRepository;
import org.cardanofoundation.lob.app.blockchain_publisher.service.keri.DocumentAttestationLookup.AttestedDispatchData;
import org.cardanofoundation.lob.app.document_vault.service.VaultProblems;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.ConsumedAttestation;
import org.cardanofoundation.lob.app.keri_attestation.service.AttestationConsumptionApi;
import org.cardanofoundation.lob.app.keri_attestation.service.Cip170MetadataFactory;

/**
 * {@link DocumentAttestationLookup} is the dispatch-time gate of design §5.3: it must reject any
 * document dispatch carrying an {@code attestationCeremonyId} unless the freeze row exists, the
 * ceremony reached {@code CONSUMED}, and the freeze/consumed digests both match a digest recomputed
 * from the reconstructed frozen bytes - fail closed at every step, never falling back to a plain
 * publish. Uses a REAL {@link Cip170MetadataFactory} (pure, stateless) so the digest comparisons
 * under test are the actual production algorithm, not a stubbed stand-in.
 */
class DocumentAttestationLookupTest {

    private static final String DOCUMENT_ID = "doc-1";
    private static final String CEREMONY_ID = "cer-1";

    private DocumentAttestationFreezeRepository freezeRepository;
    private AttestationConsumptionApi attestationConsumptionApi;
    private final Cip170MetadataFactory cip170MetadataFactory = new Cip170MetadataFactory();

    @BeforeEach
    void setUp() {
        freezeRepository = mock(DocumentAttestationFreezeRepository.class);
        attestationConsumptionApi = mock(AttestationConsumptionApi.class);
    }

    private static final int METADATA_TAG = 1447;

    private DocumentAttestationLookup lookup() {
        return lookup(METADATA_TAG);
    }

    private DocumentAttestationLookup lookup(int metadataTag) {
        return new DocumentAttestationLookup(freezeRepository, attestationConsumptionApi, cip170MetadataFactory,
                metadataTag);
    }

    /** Builds a small, realistic 1447-style map and its correct frozen CBOR bytes + digest, mirroring
     *  what {@code DocumentAttestationTargetProvider#freezeAndDigest} actually stores. */
    private static byte[] frozenBytesFor(MetadataMap map) {
        try {
            return CborSerializationUtil.serialize(map.getMap());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MetadataMap sampleMap() {
        MetadataMap map = MetadataBuilder.createMap();
        map.put("ipfs_cid", "bafy-cid-1");
        map.put("creation_slot", "12345");
        return map;
    }

    private static DocumentAttestationFreezeEntity freeze(byte[] frozenBytes, String digestQb64, String ipfsCid) {
        DocumentAttestationFreezeEntity freeze = new DocumentAttestationFreezeEntity();
        freeze.setId("freeze-1");
        freeze.setDocumentId(DOCUMENT_ID);
        freeze.setCeremonyId(CEREMONY_ID);
        freeze.setIpfsCid(ipfsCid);
        freeze.setFrozenMetadataCbor(frozenBytes);
        freeze.setDigestQb64(digestQb64);
        freeze.setMetadataCreationSlot(12345L);
        freeze.setEnvelopeSha256("a".repeat(64));
        return freeze;
    }

    /** {@code payloadSaid} is fixed to a placeholder distinct from {@code digestQb64} here — these
     *  callers only exercise the freeze/1447-digest matching path ({@link #digestQb64}), which never
     *  reads {@code payloadSaid}. {@link #attestMapDelegatesToTheFactoryWithTheConsumedAttestationsFields}
     *  below constructs its own {@link ConsumedAttestation} directly to exercise {@code payloadSaid}
     *  specifically. */
    private static ConsumedAttestation consumed(String digestQb64) {
        return new ConsumedAttestation(CEREMONY_ID, "Eaid-1", digestQb64, "Eplaceholder-payload-said", "1447", "3");
    }

    // --- loadForDispatch: missing freeze ---

    @Test
    void loadForDispatchFailsClosedWhenNoFreezeRowExists() {
        when(freezeRepository.findByDocumentIdAndCeremonyId(DOCUMENT_ID, CEREMONY_ID)).thenReturn(Optional.empty());

        Either<ProblemDetail, AttestedDispatchData> result = lookup().loadForDispatch(DOCUMENT_ID, CEREMONY_ID);

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo(VaultProblems.ATTESTATION_FREEZE_MISSING);
        assertThat(result.getLeft().getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
        verifyNoInteractions(attestationConsumptionApi);
    }

    // --- loadForDispatch: ceremony not consumed ---

    @Test
    void loadForDispatchFailsClosedWhenTheCeremonyWasNotConsumed() {
        MetadataMap map = sampleMap();
        byte[] frozenBytes = frozenBytesFor(map);
        String digest = cip170MetadataFactory.digestOf(map);
        when(freezeRepository.findByDocumentIdAndCeremonyId(DOCUMENT_ID, CEREMONY_ID))
                .thenReturn(Optional.of(freeze(frozenBytes, digest, "bafy-cid-1")));
        when(attestationConsumptionApi.findConsumed(CEREMONY_ID)).thenReturn(Optional.empty());

        Either<ProblemDetail, AttestedDispatchData> result = lookup().loadForDispatch(DOCUMENT_ID, CEREMONY_ID);

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo(VaultProblems.ATTESTATION_FREEZE_MISSING);
        assertThat(result.getLeft().getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
    }

    // --- loadForDispatch: metadata label mismatch (M3 cross-review F3 fix) ---

    @Test
    void loadForDispatchFailsClosedWhenTheConsumedMetadataLabelDoesNotMatchTheConfiguredDispatchLabel() {
        MetadataMap map = sampleMap();
        byte[] frozenBytes = frozenBytesFor(map);
        String digest = cip170MetadataFactory.digestOf(map);
        when(freezeRepository.findByDocumentIdAndCeremonyId(DOCUMENT_ID, CEREMONY_ID))
                .thenReturn(Optional.of(freeze(frozenBytes, digest, "bafy-cid-1")));
        // The ceremony was attested under label "1447" (see consumed()), but this lookup is configured
        // with a different dispatch label - digests would still agree, so the label mismatch alone must
        // reject before any digest recomputation happens.
        when(attestationConsumptionApi.findConsumed(CEREMONY_ID)).thenReturn(Optional.of(consumed(digest)));

        Either<ProblemDetail, AttestedDispatchData> result = lookup(9999).loadForDispatch(DOCUMENT_ID, CEREMONY_ID);

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo(VaultProblems.ATTESTED_METADATA_MISMATCH);
        assertThat(result.getLeft().getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
        assertThat(result.getLeft().getDetail()).contains("1447").contains("9999");
    }

    // --- constructor: fail-fast on the reserved CIP-170 label (M3 cross-review F3 fix) ---

    @Test
    void constructorRejectsMetadataTag170SinceItIsReservedForCip170Attestations() {
        assertThatThrownBy(() -> lookup(170))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("170");
    }

    // --- loadForDispatch: digest mismatch ---

    @Test
    void loadForDispatchFailsClosedWhenTheFrozenDigestDoesNotMatchTheStoredDigest() {
        MetadataMap map = sampleMap();
        byte[] frozenBytes = frozenBytesFor(map);
        // The freeze row's stored digest disagrees with what the frozen bytes actually digest to -
        // simulates non-deterministic re-encoding or tampering.
        when(freezeRepository.findByDocumentIdAndCeremonyId(DOCUMENT_ID, CEREMONY_ID))
                .thenReturn(Optional.of(freeze(frozenBytes, "Ewrong-digest", "bafy-cid-1")));
        when(attestationConsumptionApi.findConsumed(CEREMONY_ID)).thenReturn(Optional.of(consumed("Ewrong-digest")));

        Either<ProblemDetail, AttestedDispatchData> result = lookup().loadForDispatch(DOCUMENT_ID, CEREMONY_ID);

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo(VaultProblems.ATTESTED_METADATA_MISMATCH);
        assertThat(result.getLeft().getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
    }

    @Test
    void loadForDispatchFailsClosedWhenTheFreezeDigestDisagreesButTheConsumedDigestAgrees() {
        MetadataMap map = sampleMap();
        byte[] frozenBytes = frozenBytesFor(map);
        String digest = cip170MetadataFactory.digestOf(map);
        // The freeze row's own stored digest disagrees with the recomputed one, even though the
        // ceremony's consumed digest (what the wallet actually anchored) agrees - the recomputed
        // digest must equal BOTH, not just one of them.
        when(freezeRepository.findByDocumentIdAndCeremonyId(DOCUMENT_ID, CEREMONY_ID))
                .thenReturn(Optional.of(freeze(frozenBytes, "Ewrong-freeze-digest", "bafy-cid-1")));
        when(attestationConsumptionApi.findConsumed(CEREMONY_ID)).thenReturn(Optional.of(consumed(digest)));

        Either<ProblemDetail, AttestedDispatchData> result = lookup().loadForDispatch(DOCUMENT_ID, CEREMONY_ID);

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo(VaultProblems.ATTESTED_METADATA_MISMATCH);
        assertThat(result.getLeft().getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
    }

    @Test
    void loadForDispatchFailsClosedWhenTheConsumedDigestDisagreesWithTheFreeze() {
        MetadataMap map = sampleMap();
        byte[] frozenBytes = frozenBytesFor(map);
        String digest = cip170MetadataFactory.digestOf(map);
        when(freezeRepository.findByDocumentIdAndCeremonyId(DOCUMENT_ID, CEREMONY_ID))
                .thenReturn(Optional.of(freeze(frozenBytes, digest, "bafy-cid-1")));
        // The ceremony's own consumed digest (what the wallet actually anchored) disagrees with the
        // freeze - the wallet attested something other than this frozen metadata.
        when(attestationConsumptionApi.findConsumed(CEREMONY_ID)).thenReturn(Optional.of(consumed("Esomething-else")));

        Either<ProblemDetail, AttestedDispatchData> result = lookup().loadForDispatch(DOCUMENT_ID, CEREMONY_ID);

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo(VaultProblems.ATTESTED_METADATA_MISMATCH);
    }

    // --- loadForDispatch: happy path ---

    @Test
    void loadForDispatchReturnsReconstructedFrozenMapCidAndConsumedAttestationWhenEverythingMatches() {
        MetadataMap map = sampleMap();
        byte[] frozenBytes = frozenBytesFor(map);
        String digest = cip170MetadataFactory.digestOf(map);
        when(freezeRepository.findByDocumentIdAndCeremonyId(DOCUMENT_ID, CEREMONY_ID))
                .thenReturn(Optional.of(freeze(frozenBytes, digest, "bafy-cid-1")));
        ConsumedAttestation consumedAttestation = consumed(digest);
        when(attestationConsumptionApi.findConsumed(CEREMONY_ID)).thenReturn(Optional.of(consumedAttestation));

        Either<ProblemDetail, AttestedDispatchData> result = lookup().loadForDispatch(DOCUMENT_ID, CEREMONY_ID);

        assertThat(result.isRight()).isTrue();
        AttestedDispatchData data = result.get();
        assertThat(data.ipfsCid()).isEqualTo("bafy-cid-1");
        assertThat(data.consumed()).isEqualTo(consumedAttestation);
        assertThat(data.frozenMetadataMap().get("ipfs_cid")).isEqualTo("bafy-cid-1");
        assertThat(data.frozenMetadataMap().get("creation_slot")).isEqualTo("12345");
        // The reconstructed map round-trips to the exact same canonical bytes it was frozen from.
        assertThat(frozenBytesFor(data.frozenMetadataMap())).isEqualTo(frozenBytes);
    }

    // --- attestMap ---

    @Test
    void attestMapDelegatesToTheFactoryWithTheConsumedAttestationsFields() {
        // digestQb64 and payloadSaid are deliberately distinct here (design §4.4 rev 3): attestMap must
        // use payloadSaid as the on-chain "d", never digestQb64 (the raw 1447/freeze digest).
        ConsumedAttestation consumedAttestation =
                new ConsumedAttestation(CEREMONY_ID, "Eaid-1", "Edigest-1", "Epayloadsaid-1", "1447", "7");

        MetadataMap attestMap = lookup().attestMap(consumedAttestation);

        assertThat(attestMap.get("t")).isEqualTo("ATTEST");
        assertThat(attestMap.get("i")).isEqualTo("Eaid-1");
        assertThat(attestMap.get("d")).isEqualTo("Epayloadsaid-1");
        assertThat(attestMap.get("s")).isEqualTo("7");
        verify(freezeRepository, never()).findByDocumentIdAndCeremonyId(any(), any());
    }

}
