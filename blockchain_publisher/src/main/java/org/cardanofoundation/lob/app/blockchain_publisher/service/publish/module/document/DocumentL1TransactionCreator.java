package org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import co.nstant.in.cbor.model.Map;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.exception.CborSerializationException;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.metadata.Metadata;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadataMap;
import com.bloxbean.cardano.client.metadata.helper.MetadataToJsonNoSchemaConverter;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.blockchain_common.domain.events.DocumentPublishCommand;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.Cip170MetadataFactory;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.DocumentIpfsSerialiser;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.DocumentMetadataSerialiser;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.MetadataChecker;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.API3BlockchainTransaction;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.SerializedCardanoL1Transaction;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation;
import org.cardanofoundation.lob.app.blockchain_publisher.service.ipfs.IpfsPublisher;
import org.cardanofoundation.lob.app.blockchain_reader.BlockchainReaderPublicApiIF;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;

/**
 * L1 transaction creator for documents. Standalone (NOT
 * {@link org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.AbstractL1TransactionCreator}):
 * that base treats IPFS as an optional offload and inlines the payload into L1 metadata when it is absent -
 * the exact opposite of the document requirement. Documents are one-per-tx (mirrors
 * {@link org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.report.API3L1TransactionCreator}),
 * and IPFS is mandatory: no {@link IpfsPublisher} configured means dispatch fails, full stop - the envelope is
 * never inlined into L1 metadata and never silently skipped.
 */
@Slf4j
@RequiredArgsConstructor
public class DocumentL1TransactionCreator {

    private final BackendService backendService;
    private final DocumentConverter documentConverter;
    private final DocumentIpfsSerialiser documentIpfsSerialiser;
    private final DocumentMetadataSerialiser documentMetadataSerialiser;
    private final BlockchainReaderPublicApiIF blockchainReaderPublicApi;
    private final MetadataChecker jsonSchemaMetadataChecker;
    private final OrganisationPublicApi organisationPublicApi;
    private final Account organiserWallet;
    private final Optional<IpfsPublisher> ipfsPublisher;
    private final Cip170MetadataFactory cip170MetadataFactory;

    private final int metadataTag;
    private final boolean debugStoreOutputTx;

    /** CIP-170 metadata label the ATTEST map (design §4.4) is published under - fixed by the CIP, not
     *  configurable (unlike {@link #metadataTag}, which is the document's own 1447-by-default label). */
    private static final long CIP170_ATTEST_METADATA_TAG = 170L;

    private String runId;

    @PostConstruct
    public void init() {
        log.info("DocumentL1TransactionCreator::metadata label: {}", metadataTag);
        log.info("DocumentL1TransactionCreator::debug store output tx: {}", debugStoreOutputTx);

        runId = UUID.randomUUID().toString();
        log.info("DocumentL1TransactionCreator::runId: {}", runId);

        log.info("DocumentL1TransactionCreator is initialised.");
    }

    /**
     * Builds the document's L1 transaction. Attested and plain publishes follow the SAME path and
     * differ only in whether a CIP-170 ATTEST map is attached under label 170.
     *
     * <p>They used to diverge sharply: an attested publish replayed a 1447 map frozen by the ceremony,
     * reusing its {@code ipfs_cid} verbatim and never re-pinning. That is gone, because the tier which
     * runs ceremonies has neither IPFS credentials nor chain access and so cannot build a manifest at
     * all — see the {@code api} service in cf-reeve-application's docker-compose.yml. The wallet now
     * attests a content commitment instead ({@code DocumentAttestationCommitment}), and this tier —
     * the one that actually holds those credentials — pins the envelope, reads the tip and assembles
     * the manifest, for attested and plain publishes alike.
     *
     * <p>The attestation itself arrives ON the dispatch record, put there by document_vault when it
     * consumed the ceremony. Nothing here calls back into {@code keri_attestation} or
     * {@code document_vault}; this module no longer depends on either, which is what allows the two to
     * run in separate processes.
     */
    public Either<ProblemDetail, API3BlockchainTransaction> pullBlockchainTransaction(
            String organisationId, DocumentEntity document) {
        if (ipfsPublisher.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                    "Document publishing requires IPFS; no IpfsPublisher is configured in this deployment.");
            problem.setTitle("DOCUMENT_PUBLISHING_UNAVAILABLE");

            return Either.left(problem);
        }

        DocumentPublishCommand command = documentConverter.toPublishCommand(document);
        String envelopeJson = documentIpfsSerialiser.serialise(command);

        return ipfsPublisher.get().publish(envelopeJson).flatMap(cid -> {
            document.setIpfsCid(cid);

            return blockchainReaderPublicApi.getChainTip().flatMap(chainTip -> {
                long creationSlot = chainTip.getAbsoluteSlot();
                var organisationEntity = organisationPublicApi.findByOrganisationId(command.organisationId())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Organisation not found for id: %s".formatted(command.organisationId())));
                Organisation organisation = Organisation.fromOrganisationEntity(organisationEntity);
                MetadataMap metadataMap = documentMetadataSerialiser.serialiseToMetadataMap(command, cid, creationSlot,
                        organisation.getId(), organisation.getName(), organisation.getTaxIdNumber(),
                        organisation.getCurrencyId(), organisation.getCountryCode());

                return handleTransactionCreation(metadataMap, attestMapOrNull(document), creationSlot);
            });
        });
    }

    /**
     * The CIP-170 ATTEST map for an attested publish, or {@code null} for the plain path.
     *
     * <p>{@code 170.d} carries the payload SAID the wallet's KEL actually anchored, NOT the commitment
     * digest — the two are distinct on purpose, and conflating them produced a design a real Veridian
     * wallet never accepted.
     *
     * <p>Fails closed by construction: a document carrying a ceremony id but missing the attestation
     * fields would have to have been written by a vault that skipped consumption, so treat it as an
     * error rather than quietly publishing it unattested.
     */
    private MetadataMap attestMapOrNull(DocumentEntity document) {
        if (document.getAttestationCeremonyId() == null) {
            return null;
        }
        if (document.getAttestationAid() == null || document.getAttestationPayloadSaid() == null) {
            throw new IllegalStateException(
                    "Document %s carries attestation ceremony %s but no consumed attestation; refusing to publish it as unattested."
                            .formatted(document.getId(), document.getAttestationCeremonyId()));
        }

        return cip170MetadataFactory.attestMap(document.getAttestationAid(),
                document.getAttestationPayloadSaid(), document.getAttestationKelSequence());
    }

    private Either<ProblemDetail, API3BlockchainTransaction> handleTransactionCreation(MetadataMap metadataMap,
                                                                                        long creationSlot) {
        return handleTransactionCreation(metadataMap, null, creationSlot);
    }

    /**
     * @param attestMap170OrNull the CIP-170 {@code ATTEST} map to attach under label 170 alongside the
     *                            document's own {@code metadataTag} map, or {@code null} for a plain
     *                            (non-attested) publish - the JSON-schema check below validates ONLY
     *                            the {@code metadataMap} (label {@link #metadataTag}), exactly as it
     *                            did before this parameter existed; label 170 is never schema-checked.
     */
    private Either<ProblemDetail, API3BlockchainTransaction> handleTransactionCreation(MetadataMap metadataMap,
                                                                                        MetadataMap attestMap170OrNull,
                                                                                        long creationSlot) {
        try {
            Map data = metadataMap.getMap();
            byte[] bytes = CborSerializationUtil.serialize(data);

            // we use json only for validation with json schema and for debugging (storing to a tmp file)
            String json = MetadataToJsonNoSchemaConverter.cborBytesToJson(bytes);

            Metadata metadata = MetadataBuilder.createMetadata();
            CBORMetadataMap cborMetadataMap = new CBORMetadataMap(data);

            metadata.put(metadataTag, cborMetadataMap);
            if (attestMap170OrNull != null) {
                metadata.put(CIP170_ATTEST_METADATA_TAG, attestMap170OrNull);
            }

            boolean isValid = jsonSchemaMetadataChecker.checkTransactionMetadata(json);
            if (!isValid) {
                ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Metadata is not valid according to the transaction schema, we will not create a transaction!");
                problemDetail.setTitle("INVALID_DOCUMENT_METADATA");
                return Either.left(problemDetail);
            }

            log.info("Metadata for tx validated, gonna serialise tx now...");

            byte[] serialisedTxBytes = serialiseTransaction(metadata);

            SerializedCardanoL1Transaction serializedTx = new SerializedCardanoL1Transaction(serialisedTxBytes, bytes, json);

            potentiallyStoreTxs(creationSlot, serializedTx);

            return Either.right(new API3BlockchainTransaction(creationSlot, serialisedTxBytes, organiserWallet.baseAddress()));
        } catch (Exception e) {
            log.error("Error serialising metadata to cbor", e);
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Error serialising metadata to cbor: %s".formatted(e.getMessage()));
            problemDetail.setTitle("ERROR_SERIALISING_METADATA");
            return Either.left(problemDetail);
        }
    }

    // for debug and inspection only
    private void potentiallyStoreTxs(long creationSlot, SerializedCardanoL1Transaction tx) throws IOException {
        if (debugStoreOutputTx) {
            String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
            String name = "lob-txs-document-metadata-%s-%s-%s".formatted(
                    runId,
                    timestamp,
                    creationSlot);
            Path tmpJsonTxFile = Files.createTempFile(name, ".json");
            Path tmpCborFile = Files.createTempFile(name, ".cbor");

            log.info("DebugStoreTx enabled, storing JSON tx metadata to file: {}", tmpJsonTxFile);
            Files.writeString(tmpJsonTxFile, tx.metadataJson());

            log.info("DebugStoreTx enabled, storing CBOR tx metadata to file: {}", tmpCborFile);
            Files.write(tmpCborFile, tx.metadataCbor());
        }
    }

    protected byte[] serialiseTransaction(Metadata metadata) throws CborSerializationException {
        QuickTxBuilder quickTxBuilder = new QuickTxBuilder(backendService);

        Tx tx = new Tx()
                .payToAddress(organiserWallet.baseAddress(), Amount.ada(2.0))
                .attachMetadata(metadata)
                .from(organiserWallet.baseAddress());

        return quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(organiserWallet))
                .buildAndSign()
                .serialize();
    }

}
