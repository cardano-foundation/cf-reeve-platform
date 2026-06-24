package org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module;

import static org.apache.commons.collections4.iterators.PeekingIterator.peekingIterator;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.annotation.PostConstruct;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ProblemDetail;

import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.Map;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.exception.CborSerializationException;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.metadata.Metadata;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataList;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadataMap;
import com.bloxbean.cardano.client.metadata.helper.MetadataToJsonNoSchemaConverter;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.google.common.collect.Sets;
import io.vavr.control.Either;
import org.apache.commons.collections4.iterators.PeekingIterator;

import org.cardanofoundation.lob.app.blockchain_common.service_assistance.MetadataChecker;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.L1Batch;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.SerializedCardanoL1Transaction;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.publish.PublishableEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.service.ipfs.IpfsPublisher;
import org.cardanofoundation.lob.app.blockchain_reader.BlockchainReaderPublicApiIF;

/**
 * Shared base for L1 transaction creators that batch many publishable entities into Cardano transactions
 * (API1 transactions, spending events, ...). Subclasses only provide the type-specific metadata serialisation;
 * the chunking algorithm (fit as many entities as possible under the max tx size, defer the rest as "remaining"),
 * the optional IPFS off-loading, schema validation, signing and debug dumping all live here.
 *
 * @param <E> the publishable entity type batched by this creator
 */
@Slf4j
public abstract class AbstractL1TransactionCreator<E extends PublishableEntity> {

    protected static final int CARDANO_MAX_TRANSACTION_SIZE_BYTES = 16000;
    protected static final String ERROR_SERIALISING_TRANSACTION_ABORT_PROCESSING_ISSUE = "Error serialising transaction, abort processing, issue: {}";

    protected final BackendService backendService;
    protected final BlockchainReaderPublicApiIF blockchainReaderPublicApi;
    protected final MetadataChecker jsonSchemaMetadataChecker;
    protected final Account organiserAccount;
    protected final Optional<IpfsPublisher> ipfsPublisher;

    /** Whether this module off-loads the metadata payload to IPFS. Independent per module. */
    protected final boolean useIpfs;

    protected final int metadataLabel;
    protected final boolean debugStoreOutputTx;

    private String runId;

    protected AbstractL1TransactionCreator(BackendService backendService,
                                           BlockchainReaderPublicApiIF blockchainReaderPublicApi,
                                           MetadataChecker jsonSchemaMetadataChecker,
                                           Account organiserAccount,
                                           Optional<IpfsPublisher> ipfsPublisher,
                                           L1TransactionCreatorConfig config) {
        this.backendService = backendService;
        this.blockchainReaderPublicApi = blockchainReaderPublicApi;
        this.jsonSchemaMetadataChecker = jsonSchemaMetadataChecker;
        this.organiserAccount = organiserAccount;
        this.ipfsPublisher = ipfsPublisher;
        this.useIpfs = config.useIpfs();
        this.metadataLabel = config.metadataLabel();
        this.debugStoreOutputTx = config.debugStoreOutputTx();
    }

    /** Serialise a batch of entities into a Cardano metadata map. Type-specific; supplied by each subclass. */
    protected abstract MetadataMap serialiseToMetadataMap(String organisationId, Set<E> batch, long creationSlot);

    /** Prefix used for the debug metadata dump file names. */
    protected abstract String metadataFilePrefix();

    @PostConstruct
    public void init() {
        log.info("{}::metadata label: {}", getClass().getSimpleName(), metadataLabel);
        log.info("{}::debug store output tx: {}", getClass().getSimpleName(), debugStoreOutputTx);

        runId = UUID.randomUUID().toString();
        log.info("{}::runId: {}", getClass().getSimpleName(), runId);

        log.info("{} is initialised.", getClass().getSimpleName());
    }

    public Either<ProblemDetail, Optional<L1Batch<E>>> pullBlockchainTransaction(String organisationId, Set<E> entities) {
        return blockchainReaderPublicApi.getChainTip()
                .flatMap(chainTip -> handleTransactionCreation(organisationId, entities, chainTip.getAbsoluteSlot()));
    }

    private Either<ProblemDetail, Optional<L1Batch<E>>> handleTransactionCreation(String organisationId,
                                                                                 Set<E> entities,
                                                                                 long creationSlot) {
        try {
            if (useIpfs && ipfsPublisher.isPresent()) {
                return createIpfsL1Transaction(organisationId, entities, creationSlot);
            }
            return createL1Transaction(organisationId, entities, creationSlot);
        } catch (IOException | CborException | CborSerializationException e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(INTERNAL_SERVER_ERROR, "%s".formatted(e.getMessage()));
            log.error("Error creating blockchain transaction: ", e);
            problemDetail.setTitle("ERROR_CREATING_TRANSACTION");
            return Either.left(problemDetail);
        }
    }

    private Either<ProblemDetail, Optional<L1Batch<E>>> createIpfsL1Transaction(String organisationId, Set<E> entities, long creationSlot) throws CborException, CborSerializationException {
        // Creating one transaction containing all entities, probably it's too big, but that's why we replace the metadata data with an IPFS link
        MetadataMap metadataMap = serialiseToMetadataMap(organisationId, entities, creationSlot);

        Map data = metadataMap.getMap();
        Either<ProblemDetail, Void> problemDetail = checkJsonSchema(data);
        if (problemDetail.isLeft()) {
            return Either.left(problemDetail.getLeft());
        }

        // Removing the data object from the metadata and replacing it with an ipfs link to the data,
        // to reduce the size of the metadata and be able to create a transaction with more entities in it
        MetadataList dataList = (MetadataList) metadataMap.get("data");
        metadataMap.remove("data");
        MetadataMap ipfsMap = MetadataBuilder.createMap();
        ipfsMap.put("data", dataList);
        byte[] bytes = CborSerializationUtil.serialize(ipfsMap.getMap());
        String json = MetadataToJsonNoSchemaConverter.cborBytesToJson(bytes);
        if (ipfsPublisher.isEmpty()) {
            ProblemDetail problemDetailIpfs = ProblemDetail.forStatusAndDetail(INTERNAL_SERVER_ERROR, "IPFS publisher is not configured, we cannot publish the metadata to IPFS!");
            problemDetailIpfs.setTitle("IPFS_PUBLISHER_NOT_CONFIGURED");
            return Either.left(problemDetailIpfs);
        }
        Either<ProblemDetail, String> publish = ipfsPublisher.get().publish(json);
        if (publish.isLeft()) {
            log.error("Error publishing metadata to IPFS, issue: {}", publish.getLeft().getDetail());
            return Either.left(publish.getLeft());
        }
        String cid = publish.get();
        metadataMap.put("ipfs", cid);

        Metadata metadata = MetadataBuilder.createMetadata();
        CBORMetadataMap cborMetadataMap = new CBORMetadataMap(data);

        metadata.put(metadataLabel, cborMetadataMap);
        byte[] serialisedTx = serialiseTransaction(metadata);
        log.info("Metadata for tx validated, gonna serialise tx now...");
        return Either.right(Optional.of(new L1Batch<>(
                organisationId,
                entities,
                Set.of(), // Empty - we published all entities
                creationSlot,
                serialisedTx,
                organiserAccount.baseAddress()
        )));
    }

    private Either<ProblemDetail, Void> checkJsonSchema(Map data) throws CborException {
        byte[] bytes = CborSerializationUtil.serialize(data);

        // we use json only for validation with json schema and for debugging (storing to a tmp file)
        String json = MetadataToJsonNoSchemaConverter.cborBytesToJson(bytes);
        boolean isValid = jsonSchemaMetadataChecker.checkTransactionMetadata(json);
        if (!isValid) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(INTERNAL_SERVER_ERROR, "Metadata is not valid according to the transaction schema, we will not create a transaction!");
            problemDetail.setTitle("INVALID_TRANSACTION_METADATA");
            return Either.left(problemDetail);
        }
        return Either.right(null);
    }

    // error or entities to process or no more entities to process in case of blockchain transaction creation
    private Either<ProblemDetail, Optional<L1Batch<E>>> createL1Transaction(String organisationId,
                                                                            Set<E> entities,
                                                                            long creationSlot) throws IOException {
        log.info("Splitting {} entities into blockchain transactions", entities.size());

        LinkedHashSet<E> batch = new LinkedHashSet<>();

        for (PeekingIterator<E> it = peekingIterator(entities.iterator()); it.hasNext();) {
            E entity = it.next();

            batch.add(entity);

            Either<ProblemDetail, SerializedCardanoL1Transaction> serializedTransactionsE = serialiseTransactionChunk(organisationId, batch, creationSlot);
            if (serializedTransactionsE.isLeft()) {
                log.error(ERROR_SERIALISING_TRANSACTION_ABORT_PROCESSING_ISSUE, serializedTransactionsE.getLeft().getDetail());

                return Either.left(serializedTransactionsE.getLeft());
            }

            SerializedCardanoL1Transaction serializedTransaction = serializedTransactionsE.get();
            byte[] txBytes = serializedTransaction.txBytes();

            E entityPeek = it.peek();
            if (entityPeek == null) { // next one is last element
                continue;
            }
            Either<ProblemDetail, SerializedCardanoL1Transaction> newChunkTxBytesE = serialiseTransactionChunk(organisationId, Stream.concat(batch.stream(), Stream.of(entityPeek))
                    .collect(Collectors.toSet()), creationSlot);

            if (newChunkTxBytesE.isLeft()) {
                log.error(ERROR_SERIALISING_TRANSACTION_ABORT_PROCESSING_ISSUE, newChunkTxBytesE.getLeft().getDetail());

                return Either.left(newChunkTxBytesE.getLeft());
            }
            SerializedCardanoL1Transaction newSerializedTransaction = newChunkTxBytesE.get();
            byte[] newChunkTxBytes = newSerializedTransaction.txBytes();

            if (newChunkTxBytes.length >= CARDANO_MAX_TRANSACTION_SIZE_BYTES) {
                log.info("Blockchain transaction created, id:{}, debugTxOutput:{}", TransactionUtil.getTxHash(txBytes), this.debugStoreOutputTx);
                potentiallyStoreTxs(creationSlot, serializedTransaction);

                Set<E> remaining = calculateRemainingEntities(entities, batch);

                return Either.right(Optional.of(new L1Batch<>(organisationId, batch, remaining, creationSlot, txBytes, organiserAccount.baseAddress())));
            }
        }

        // if there are any left overs, meaning that the batch is not full, e.g. just a couple of entities to serialise
        if (!batch.isEmpty()) {
            log.info("Leftovers batch size: {}", batch.size());

            Either<ProblemDetail, SerializedCardanoL1Transaction> serializedTxE = serialiseTransactionChunk(organisationId, batch, creationSlot);

            if (serializedTxE.isEmpty()) {
                log.error(ERROR_SERIALISING_TRANSACTION_ABORT_PROCESSING_ISSUE, serializedTxE.getLeft().getDetail());

                return Either.left(serializedTxE.getLeft());
            }

            SerializedCardanoL1Transaction serTx = serializedTxE.get();
            byte[] txBytes = serTx.txBytes();
            log.info("Blockchain transaction created, id:{}, debugTxOutput:{}", TransactionUtil.getTxHash(txBytes), this.debugStoreOutputTx);

            potentiallyStoreTxs(creationSlot, serTx);

            log.info("Transaction size: {}", txBytes.length);

            Set<E> remaining = calculateRemainingEntities(entities, batch);

            return Either.right(Optional.of(new L1Batch<>(
                    organisationId,
                    batch,
                    remaining,
                    creationSlot,
                    txBytes,
                    organiserAccount.baseAddress()
            )));
        }

        // no entities to process
        return Either.right(Optional.empty());
    }

    // for debug and inspection only
    private void potentiallyStoreTxs(long creationSlot, SerializedCardanoL1Transaction tx) throws IOException {
        if (debugStoreOutputTx) {
            String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
            String name = "%s-%s-%s-%s".formatted(metadataFilePrefix(), runId, timestamp, creationSlot);
            FileAttribute<Set<PosixFilePermission>> ownerOnly =
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
            Path tmpJsonTxFile = Files.createTempFile(name, ".json", ownerOnly);
            Path tmpCborFile = Files.createTempFile(name, ".cbor", ownerOnly);

            log.info("DebugStoreTx enabled, storing JSON tx metadata to file: {}", tmpJsonTxFile);
            Files.writeString(tmpJsonTxFile, tx.metadataJson());

            log.info("DebugStoreTx enabled, storing CBOR tx metadata to file: {}", tmpCborFile);
            Files.write(tmpCborFile, tx.metadataCbor());
        }
    }

    private Set<E> calculateRemainingEntities(Set<E> entities, Set<E> batch) {
        return Sets.difference(entities, batch);
    }

    private Either<ProblemDetail, SerializedCardanoL1Transaction> serialiseTransactionChunk(String organisationId,
                                                                                           Set<E> batch,
                                                                                           long creationSlot) {
        try {
            MetadataMap metadataMap = serialiseToMetadataMap(organisationId, batch, creationSlot);

            Map data = metadataMap.getMap();
            byte[] bytes = CborSerializationUtil.serialize(data);

            // we use json only for validation with json schema and for debugging (storing to a tmp file)
            String json = MetadataToJsonNoSchemaConverter.cborBytesToJson(bytes);
            boolean isValid = jsonSchemaMetadataChecker.checkTransactionMetadata(json);
            if (!isValid) {
                ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(INTERNAL_SERVER_ERROR, "Metadata is not valid according to the transaction schema, we will not create a transaction!");
                problemDetail.setTitle("INVALID_TRANSACTION_METADATA");
                return Either.left(problemDetail);
            }

            Metadata metadata = MetadataBuilder.createMetadata();
            CBORMetadataMap cborMetadataMap = new CBORMetadataMap(data);

            metadata.put(metadataLabel, cborMetadataMap);

            log.info("Metadata for tx validated, gonna serialise tx now...");

            byte[] serialisedTx = serialiseTransaction(metadata);

            return Either.right(new SerializedCardanoL1Transaction(serialisedTx, bytes, json));
        } catch (Exception e) {
            log.error("Error serialising metadata to cbor", e);
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(INTERNAL_SERVER_ERROR, "%s".formatted(e.getMessage()));
            problemDetail.setTitle("ERROR_SERIALISING_METADATA");
            return Either.left(problemDetail);
        }
    }

    public byte[] serialiseTransaction(Metadata metadata) throws CborSerializationException {
        QuickTxBuilder quickTxBuilder = new QuickTxBuilder(backendService);

        Tx tx = new Tx()
                .payToAddress(organiserAccount.baseAddress(), Amount.ada(2.0))
                .attachMetadata(metadata)
                .from(organiserAccount.baseAddress());

        return quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(organiserAccount))
                .buildAndSign()
                .serialize();
    }

}
