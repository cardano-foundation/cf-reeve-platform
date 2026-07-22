package org.cardanofoundation.lob.app.blockchain_publisher.service.keri;

import java.util.Optional;

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
 *   <li><b>metadata label mismatch</b> (M3 cross-review F3 fix) - {@code consumed.metadataLabel()}
 *       (the Cardano metadata label the ceremony's frozen digest was actually anchored under, set at
 *       ATTEST time from the SAME configured label {@link #metadataTag} carries here) must equal
 *       {@link #metadataTag} - the label {@code DocumentL1TransactionCreator} is ACTUALLY about to
 *       dispatch this document under. A mismatch means the label this deployment publishes under was
 *       reconfigured between freeze/ATTEST time and dispatch time; publishing under the new label
 *       while the attestation still names the old one would produce an on-chain CIP-170 attestation
 *       that no longer describes where the document metadata actually landed.</li>
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
public class DocumentAttestationLookup {

    /** CIP-170 attestation metadata itself is always published under label 170 ({@code
     *  Cip170MetadataFactory}) - reserved and never available for {@link #metadataTag} (M3 cross-review
     *  F3 fix). See this class's constructor for the fail-fast check. */
    private static final int RESERVED_CIP_170_LABEL = 170;

    private final DocumentAttestationFreezeRepository freezeRepository;
    private final AttestationConsumptionApi attestationConsumptionApi;
    private final Cip170MetadataFactory cip170MetadataFactory;
    /**
     * The SAME {@code ${lob.l1.transaction.metadata_label:1447}} value {@code TransactionSubmissionConfig}
     * wires into {@code documentL1TransactionCreator}'s {@code metadataTag} and {@code
     * DocumentAttestationTargetProvider}'s {@code metadataLabel} (M3 milestone-review finding those two
     * beans already share) - threaded through here too so {@link #loadForDispatch} can enforce that the
     * ceremony's persisted {@code metadataLabel} still agrees with the label dispatch is ACTUALLY about
     * to publish under (M3 cross-review F3 fix), not just with what it was at freeze time.
     */
    private final int metadataTag;

    /**
     * @throws IllegalStateException if {@code metadataTag} is {@code 170} - that
     *         label is reserved for the CIP-170 attestation map itself ({@link Cip170MetadataFactory}).
     *         A deployment configuring the document metadata label to 170 would make an attested
     *         dispatch overwrite (or collide with) its own attestation under the same label; refusing
     *         to start is strictly better than silently corrupting on-chain metadata the first time a
     *         document is dispatched (M3 cross-review F3 fix).
     */
    public DocumentAttestationLookup(DocumentAttestationFreezeRepository freezeRepository,
            AttestationConsumptionApi attestationConsumptionApi, Cip170MetadataFactory cip170MetadataFactory,
            int metadataTag) {
        if (metadataTag == RESERVED_CIP_170_LABEL) {
            throw new IllegalStateException("metadata label 170 is reserved for CIP-170 attestations");
        }
        this.freezeRepository = freezeRepository;
        this.attestationConsumptionApi = attestationConsumptionApi;
        this.cip170MetadataFactory = cip170MetadataFactory;
        this.metadataTag = metadataTag;
    }

    /**
     * Everything {@code DocumentL1TransactionCreator} needs to build an attested dispatch: the frozen
     * label-1447 map reconstructed byte-identically from {@code frozenMetadataCbor} (reused verbatim,
     * never re-serialised), the frozen IPFS cid to reuse verbatim (no re-upload), and the ceremony's
     * {@link ConsumedAttestation} to build the label-170 {@code ATTEST} map from.
     */
    public record AttestedDispatchData(MetadataMap frozenMetadataMap, String ipfsCid, ConsumedAttestation consumed) {
    }

    /**
     * Runs the four checks in the class javadoc's ordered list (missing freeze, non-consumed
     * ceremony, metadata label mismatch, digest mismatch) and nothing else.
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

        // M3 cross-review F3 fix: the ceremony's persisted metadataLabel must still agree with the
        // label this dispatch is actually about to publish under - checked before the (more expensive)
        // digest recomputation below, cheapest-first like every other check in this method.
        if (!consumed.metadataLabel().equals(String.valueOf(metadataTag))) {
            return Either.left(labelMismatchProblem(documentId, ceremonyId, consumed.metadataLabel(), metadataTag));
        }

        MetadataMap frozenMap = reconstruct(freeze.getFrozenMetadataCbor());
        String recomputed = cip170MetadataFactory.digestOf(frozenMap);
        if (!recomputed.equals(freeze.getDigestQb64()) || !recomputed.equals(consumed.digestQb64())) {
            return Either.left(mismatchProblem(documentId, ceremonyId, recomputed, freeze.getDigestQb64(), consumed.digestQb64()));
        }

        return Either.right(new AttestedDispatchData(frozenMap, freeze.getIpfsCid(), consumed));
    }

    /** Delegates to {@link Cip170MetadataFactory#attestMap} - the creator's dependency surface stays
     *  limited to this class rather than needing its own {@code Cip170MetadataFactory} import.
     *
     *  <p>Passes {@link ConsumedAttestation#payloadSaid()}, NOT {@link ConsumedAttestation#digestQb64()},
     *  as the on-chain {@code 170.d} (design §4.4 rev 3): the wallet's KEL interaction event anchors the
     *  SAID of the saidified remotesign request payload, not the raw 1447 digest directly - see
     *  {@code ConsumedAttestation}'s javadoc. {@code digestQb64} is used elsewhere in this class only
     *  for the freeze/1447-digest match in {@link #loadForDispatch} - never for the on-chain map. */
    public MetadataMap attestMap(ConsumedAttestation consumed) {
        return cip170MetadataFactory.attestMap(consumed.aid(), consumed.payloadSaid(), consumed.kelSequence());
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

    private static ProblemDetail labelMismatchProblem(String documentId, String ceremonyId, String consumedLabel,
            int dispatchMetadataTag) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                ("Ceremony %s attested document %s under metadata label %s, but dispatch is configured to publish "
                        + "under label %d.").formatted(ceremonyId, documentId, consumedLabel, dispatchMetadataTag));
        problem.setTitle(VaultProblems.ATTESTED_METADATA_MISMATCH);
        return problem;
    }

}
