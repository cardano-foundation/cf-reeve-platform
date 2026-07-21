package org.cardanofoundation.lob.app.blockchain_publisher.service.keri;

import java.util.Optional;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadataMap;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentAttestationFreezeEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.DocumentAttestationFreezeRepository;
import org.cardanofoundation.lob.app.document_vault.service.VaultProblems;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.ConsumedAttestation;
import org.cardanofoundation.lob.app.keri_attestation.service.AttestationConsumptionApi;
import org.cardanofoundation.lob.app.keri_attestation.service.Cip170MetadataFactory;

/**
 * {@code DocumentL1TransactionCreator}'s dispatch-time attestation gate (design §5.3, Task 15): when
 * a document's dispatch record carries an {@code attestationCeremonyId}, this class is the ONLY way
 * the creator is allowed to obtain the metadata it publishes - it never re-serialises the envelope,
 * never re-uploads to IPFS, and never falls back to a plain publish. Every check below fails closed:
 * a missing freeze row, a ceremony that never reached {@code CONSUMED}, or a digest that does not
 * verify all reject dispatch outright rather than silently degrading to an unattested transaction.
 *
 * <p>Checked in this order (cheapest first, mirrors {@code DocumentAttestationFreezeGuard}'s own
 * ordering rationale - the publish-time twin of this class):
 * <ol>
 *   <li><b>missing freeze</b> - no {@code document_attestation_freeze} row for this
 *       {@code (documentId, ceremonyId)} pair; impossible by construction unless the ceremony never
 *       reached ATTEST.</li>
 *   <li><b>non-consumed ceremony</b> - {@link AttestationConsumptionApi#findConsumed} returns empty:
 *       the ceremony is not in {@code CONSUMED} state (a dispatch record should only ever carry a
 *       ceremony id that {@code VaultDocumentService#publish} already consumed via {@code
 *       validateAndConsume}, so this should also be unreachable in practice).</li>
 *   <li><b>digest mismatch</b> - the digest recomputed from the reconstructed frozen bytes must equal
 *       BOTH the freeze row's own {@code digestQb64} (guards non-deterministic CBOR re-encoding
 *       between freeze time and dispatch time) AND the ceremony's consumed {@code digestQb64} (guards
 *       the freeze and the on-chain-anchored attestation having drifted apart, e.g. under a superseded
 *       ceremony).</li>
 * </ol>
 *
 * <p>Not annotated {@code @Service} - wired as a {@code @Bean} (matching the {@code
 * DocumentAttestationTargetProvider} / {@code DocumentAttestationFreezeGuard} precedent, Tasks 13/14),
 * conditional on {@code lob.keri-attestation.enabled}.
 */
@RequiredArgsConstructor
public class DocumentAttestationLookup {

    private final DocumentAttestationFreezeRepository freezeRepository;
    private final AttestationConsumptionApi attestationConsumptionApi;
    private final Cip170MetadataFactory cip170MetadataFactory;

    /**
     * Everything {@code DocumentL1TransactionCreator} needs to build an attested dispatch: the frozen
     * label-1447 map reconstructed byte-identically from {@code frozenMetadataCbor} (reused verbatim,
     * never re-serialised), the frozen IPFS cid to reuse verbatim (no re-upload), and the ceremony's
     * {@link ConsumedAttestation} to build the label-170 {@code ATTEST} map from.
     */
    public record AttestedDispatchData(MetadataMap frozenMetadataMap, String ipfsCid, ConsumedAttestation consumed) {
    }

    /**
     * Runs the three checks in the class javadoc's ordered list (missing freeze, non-consumed
     * ceremony, digest mismatch) and nothing else.
     *
     * <p><b>Deliberately no freeze-age check here.</b> {@code DocumentAttestationFreezeGuard} (Task
     * 14, design §5.2) is the ONLY place a freeze's age is ever checked - once, synchronously, inside
     * {@code VaultDocumentService#publish}'s row-locked transaction, immediately before {@code
     * validateAndConsume} flips the ceremony to {@code CONSUMED} (and the document to {@code
     * PUBLISHED}). By the time a dispatch record reaches this method, that check has already run and
     * passed; re-running it here would be redundant at best. Worse, it would be actively wrong: design
     * §5.2's own retention rule (the F10 fix - {@code CeremonyCleanupJob}'s sweep excludes {@code
     * CONSUMED} ceremonies from purge specifically so a late dispatch retry can still read them) means
     * a freeze backing a {@code CONSUMED} ceremony is kept forever on purpose. A dispatcher that falls
     * behind, or a retry sweep that fires after {@code keri-attestation.freeze-max-age} has elapsed,
     * must still be able to publish an already-committed attestation - an age check here would turn a
     * transient dispatch delay into a permanently stuck document (already {@code PUBLISHED}
     * user-side, never actually reaching L1). Freeze age is therefore a one-time, consume-time gate on
     * whether an attestation may be *accepted*, never a recurring gate on whether an already-accepted
     * one may still be *dispatched*.
     */
    public Either<ProblemDetail, AttestedDispatchData> loadForDispatch(String documentId, String ceremonyId) {
        Optional<DocumentAttestationFreezeEntity> freezeM =
                freezeRepository.findByDocumentIdAndCeremonyId(documentId, ceremonyId);
        if (freezeM.isEmpty()) {
            return Either.left(freezeMissingProblem(documentId, ceremonyId,
                    "No attestation freeze found for this document/ceremony pair."));
        }
        DocumentAttestationFreezeEntity freeze = freezeM.get();

        Optional<ConsumedAttestation> consumedM = attestationConsumptionApi.findConsumed(ceremonyId);
        if (consumedM.isEmpty()) {
            return Either.left(freezeMissingProblem(documentId, ceremonyId,
                    "Ceremony %s has not reached CONSUMED; dispatch cannot proceed.".formatted(ceremonyId)));
        }
        ConsumedAttestation consumed = consumedM.get();

        MetadataMap frozenMap = reconstruct(freeze.getFrozenMetadataCbor());
        String recomputed = cip170MetadataFactory.digestOf(frozenMap);
        if (!recomputed.equals(freeze.getDigestQb64()) || !recomputed.equals(consumed.digestQb64())) {
            return Either.left(mismatchProblem(documentId, ceremonyId, recomputed, freeze.getDigestQb64(), consumed.digestQb64()));
        }

        return Either.right(new AttestedDispatchData(frozenMap, freeze.getIpfsCid(), consumed));
    }

    /** Delegates to {@link Cip170MetadataFactory#attestMap} - the creator's dependency surface stays
     *  limited to this class rather than needing its own {@code Cip170MetadataFactory} import. */
    public MetadataMap attestMap(ConsumedAttestation consumed) {
        return cip170MetadataFactory.attestMap(consumed.aid(), consumed.digestQb64(), consumed.kelSequence());
    }

    /** Inverts {@code CborSerializationUtil.serialize(metadataMap.getMap())}, the exact call {@code
     *  DocumentAttestationTargetProvider#freezeAndDigest} used to produce {@code frozenMetadataCbor}. */
    private static MetadataMap reconstruct(byte[] frozenMetadataCbor) {
        DataItem item = CborSerializationUtil.deserialize(frozenMetadataCbor);
        return new CBORMetadataMap((Map) item);
    }

    private static ProblemDetail freezeMissingProblem(String documentId, String ceremonyId, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                "Dispatch of document %s under ceremony %s cannot proceed: %s".formatted(documentId, ceremonyId, detail));
        problem.setTitle(VaultProblems.ATTESTATION_FREEZE_MISSING);
        return problem;
    }

    private static ProblemDetail mismatchProblem(String documentId, String ceremonyId, String recomputed,
            String frozenDigest, String consumedDigest) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                "Recomputed digest %s for document %s / ceremony %s does not match freeze digest %s / consumed digest %s."
                        .formatted(recomputed, documentId, ceremonyId, frozenDigest, consumedDigest));
        problem.setTitle(VaultProblems.ATTESTED_METADATA_MISMATCH);
        return problem;
    }

}
