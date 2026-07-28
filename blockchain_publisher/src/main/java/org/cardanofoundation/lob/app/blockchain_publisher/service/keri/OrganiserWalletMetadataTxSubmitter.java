package org.cardanofoundation.lob.app.blockchain_publisher.service.keri;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.model.Block;
import com.bloxbean.cardano.client.backend.model.TransactionContent;
import com.bloxbean.cardano.client.backend.model.metadata.MetadataJSONContent;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.metadata.Metadata;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.keri_attestation.service.CardanoMetadataTxSubmitter;

/**
 * Implements {@code keri_attestation}'s {@link CardanoMetadataTxSubmitter} port using the organiser
 * {@link Account} and {@link BackendService} this module already owns: builds, signs and submits a
 * transaction carrying only the given metadata, then reads back its confirmation depth and its
 * label-170 (CIP-170) content.
 *
 * <p>Transaction composition is isolated behind the protected {@link #submitTransaction(Metadata)}
 * seam so tests can stub it instead of exercising real network I/O.
 */
@Slf4j
@RequiredArgsConstructor
public class OrganiserWalletMetadataTxSubmitter implements CardanoMetadataTxSubmitter {

    /** CIP-170 metadata label, as the backend's metadata-service label string. */
    private static final String CIP170_LABEL = "170";

    /** Problem title for any failure while building, signing or submitting the AUTH_BEGIN tx. */
    public static final String AUTH_BEGIN_SUBMISSION_FAILED = "AUTH_BEGIN_SUBMISSION_FAILED";

    private final BackendService backendService;
    private final Account organiserWallet;
    private final ObjectMapper objectMapper;

    @Override
    public Either<ProblemDetail, String> submitMetadataTransaction(long label, MetadataMap metadata) {
        try {
            Metadata txMetadata = MetadataBuilder.createMetadata();
            txMetadata.put(label, metadata);

            Result<String> result = submitTransaction(txMetadata);

            if (!result.isSuccessful()) {
                log.warn("Failed to submit AUTH_BEGIN metadata transaction, label:{}, response:{}", label, result.getResponse());

                return Either.left(submissionFailedProblem(result.getResponse()));
            }

            String txHash = result.getValue();
            log.info("AUTH_BEGIN metadata transaction submitted, label:{}, txHash:{}", label, txHash);

            return Either.right(txHash);
        } catch (Exception e) {
            log.error("Error submitting AUTH_BEGIN metadata transaction, label:{}", label, e);

            return Either.left(submissionFailedProblem(e.getMessage()));
        }
    }

    @Override
    public Optional<Long> confirmations(String txHash) {
        try {
            Result<TransactionContent> txResult = backendService.getTransactionService().getTransaction(txHash);
            if (!txResult.isSuccessful() || txResult.getValue() == null) {
                return Optional.empty();
            }

            Long txBlockHeight = txResult.getValue().getBlockHeight();
            if (txBlockHeight == null) {
                return Optional.empty();
            }

            Result<Block> latestBlockResult = backendService.getBlockService().getLatestBlock();
            if (!latestBlockResult.isSuccessful() || latestBlockResult.getValue() == null) {
                return Optional.empty();
            }

            long latestHeight = latestBlockResult.getValue().getHeight();

            return Optional.of(latestHeight - txBlockHeight + 1);
        } catch (ApiException e) {
            log.warn("Error while computing confirmations, txHash:{}", txHash, e);

            return Optional.empty();
        }
    }

    @Override
    public Optional<Map<String, Object>> readCip170Metadata(String txHash) {
        try {
            Result<List<MetadataJSONContent>> result = backendService.getMetadataService().getJSONMetadataByTxnHash(txHash);
            if (!result.isSuccessful() || result.getValue() == null) {
                return Optional.empty();
            }

            return result.getValue().stream()
                    .filter(content -> CIP170_LABEL.equals(content.getLabel()))
                    .findFirst()
                    .map(content -> objectMapper.convertValue(content.getJsonMetadata(), new TypeReference<Map<String, Object>>() { }));
        } catch (ApiException e) {
            log.warn("Error while reading CIP-170 metadata, txHash:{}", txHash, e);

            return Optional.empty();
        }
    }

    /**
     * Builds, signs and submits the AUTH_BEGIN tx: 2 ADA organiser-to-organiser, carrying only the
     * given metadata. Protected so tests can stub the network-touching QuickTx composition and assert
     * only the metadata assembly and error mapping in
     * {@link #submitMetadataTransaction(long, MetadataMap)}.
     */
    protected Result<String> submitTransaction(Metadata metadata) {
        QuickTxBuilder quickTxBuilder = new QuickTxBuilder(backendService);

        Tx tx = new Tx()
                .payToAddress(organiserWallet.baseAddress(), Amount.ada(2.0))
                .attachMetadata(metadata)
                .from(organiserWallet.baseAddress());

        return quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(organiserWallet))
                .completeAndWait();
    }

    private static ProblemDetail submissionFailedProblem(String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "Failed to submit AUTH_BEGIN metadata transaction: %s".formatted(detail));
        problemDetail.setTitle(AUTH_BEGIN_SUBMISSION_FAILED);

        return problemDetail;
    }

}
