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
import org.cardanofoundation.lob.app.blockchain_common.service.ipfs.IpfsPublisher;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.Cip170MetadataFactory;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.DocumentIpfsSerialiser;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.DocumentMetadataSerialiser;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.MetadataChecker;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.API3BlockchainTransaction;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.SerializedCardanoL1Transaction;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation;
import org.cardanofoundation.lob.app.blockchain_reader.BlockchainReaderPublicApiIF;
import org.cardanofoundation.lob.app.keri_attestation.service.RemotesignRequestFactory;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;

/**
 * L1 transaction creator for documents: one document per transaction.
 *
 * <p>Standalone rather than extending
 * {@link org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.AbstractL1TransactionCreator},
 * because that base treats IPFS as an optional offload and inlines the payload into L1 metadata when
 * it is absent. For documents IPFS is mandatory: without an {@link IpfsPublisher} dispatch fails, and
 * the envelope is never inlined into L1 metadata.
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
    private final RemotesignRequestFactory remotesignRequestFactory;

    private final int metadataTag;
    private final boolean debugStoreOutputTx;

    /** Label the ATTEST map is published under. Fixed by CIP-170, unlike the configurable
     *  {@link #metadataTag} the document's own manifest uses. */
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
     * Builds the document's L1 transaction. Attested and plain publishes take the same path — pin the
     * envelope, read the chain tip, assemble the manifest — and differ only in whether a CIP-170
     * ATTEST map is attached under label 170.
     *
     * <p>The attestation arrives on the dispatch record, written by document_vault when it consumed
     * the ceremony. Nothing here calls back into {@code keri_attestation} or {@code document_vault},
     * which is what lets those modules run in a separate process.
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
                // creationSlot is still read and still persisted on the entity (l1_creation_slot) and
                // used for the L1 transaction — it just no longer goes INTO the manifest, which has to
                // stay derivable by a wallet that has no chain tip. See
                // L1MetadataSections#attestableMetadataSection.
                MetadataMap metadataMap = documentMetadataSerialiser.serialiseToMetadataMap(command, cid,
                        organisation.getId(), organisation.getName(), organisation.getTaxIdNumber(),
                        organisation.getCurrencyId(), organisation.getCountryCode());

                Optional<ProblemDetail> attestationDrift = verifyAttestationCoversThisManifest(document, metadataMap);
                if (attestationDrift.isPresent()) {
                    return Either.left(attestationDrift.get());
                }

                return handleTransactionCreation(metadataMap, attestMapOrNull(document), creationSlot);
            });
        });
    }

    /**
     * For an attested document, proves the manifest about to be published is the one the wallet signed.
     *
     * <p>Rebuilds the wallet's anchored payload SAID from THIS manifest — digest it, wrap it in the same
     * remotesign payload the ceremony used, take its SAID — and requires the result to equal the SAID
     * the wallet actually sealed. Any divergence at all fails the publish: a different CID because the
     * envelope changed, a different organisation record, a different recipient set. Publishing anyway
     * would put a manifest on chain under an ATTEST that authorises a different one, which is precisely
     * the claim the whole attestation exists to make.
     *
     * <p>This is the only end-to-end check of that equality. The vault's freeze guard compares envelope
     * bytes, which catches a changed document but not a manifest assembled differently here.
     *
     * @return a problem when the attestation does not cover this manifest, empty when it does or when
     *         the document is not attested at all.
     */
    private Optional<ProblemDetail> verifyAttestationCoversThisManifest(DocumentEntity document,
            MetadataMap metadataMap) {
        // An incomplete attestation is attestMapOrNull's business, not this method's: it fails those
        // closed with a louder error, and pre-empting it here would only mask which invariant broke.
        if (document.getAttestationCeremonyId() == null
                || document.getAttestationAid() == null
                || document.getAttestationPayloadSaid() == null) {
            return Optional.empty();
        }
        String expectedPayloadSaid = (String) remotesignRequestFactory.anchorRequestKed(
                document.getAttestationAid(), String.valueOf(metadataTag),
                cip170MetadataFactory.digestOf(metadataMap)).get("d");

        if (expectedPayloadSaid.equals(document.getAttestationPayloadSaid())) {
            return Optional.empty();
        }

        log.error("Document {} would publish a manifest its attestation does not cover: the wallet sealed {} "
                        + "but this manifest yields {}. Refusing to publish.",
                document.getId(), document.getAttestationPayloadSaid(), expectedPayloadSaid);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                ("Document %s carries an attestation over a different manifest than the one assembled for "
                        + "publication; the document or its organisation changed after it was attested.")
                        .formatted(document.getId()));
        problem.setTitle("ATTESTED_MANIFEST_MISMATCH");

        return Optional.of(problem);
    }

    /**
     * The CIP-170 ATTEST map for an attested publish, or {@code null} for the plain path. {@code 170.d}
     * carries the payload SAID the wallet's KEL anchored, not the commitment digest.
     *
     * <p>Fails closed: a document with a ceremony id but no attestation fields could only come from a
     * vault that skipped consumption, so it is rejected rather than published as unattested.
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

    /**
     * @param attestMap170OrNull the CIP-170 {@code ATTEST} map to attach under label 170 alongside the
     *                           document's own {@link #metadataTag} map, or {@code null} for a plain
     *                           publish. Only the document manifest is schema-checked; label 170 is
     *                           not.
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
