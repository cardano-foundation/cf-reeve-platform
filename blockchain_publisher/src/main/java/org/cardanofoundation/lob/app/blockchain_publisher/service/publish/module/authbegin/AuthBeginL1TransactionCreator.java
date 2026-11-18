package org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.authbegin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.exception.CborSerializationException;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.metadata.Metadata;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.blockchain_common.service_assistance.Cip170MetadataFactory;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.API3BlockchainTransaction;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.authbegin.AuthBeginEntity;
import org.cardanofoundation.lob.app.blockchain_reader.BlockchainReaderPublicApiIF;

/**
 * Builds the CIP-170 AUTH_BEGIN transaction: one ceremony per transaction, carrying nothing but the
 * label-170 map.
 *
 * <p>Standalone rather than extending
 * {@link org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.AbstractL1TransactionCreator}
 * because that base is built around an organisation manifest with an optional IPFS offload; AUTH_BEGIN
 * has neither. There is also no JSON schema to check — label 170's shape is fixed by the CIP and
 * produced by {@link Cip170MetadataFactory}, not by a serialiser this module could validate.
 */
@Slf4j
@RequiredArgsConstructor
public class AuthBeginL1TransactionCreator {

    /** Fixed by CIP-170. Unlike the document manifest label, this is never configurable. */
    private static final long CIP170_METADATA_LABEL = 170L;

    private final BackendService backendService;
    private final BlockchainReaderPublicApiIF blockchainReaderPublicApi;
    private final Cip170MetadataFactory cip170MetadataFactory;
    private final Account organiserWallet;

    public Either<ProblemDetail, API3BlockchainTransaction> pullBlockchainTransaction(AuthBeginEntity authBegin) {
        return blockchainReaderPublicApi.getChainTip().flatMap(chainTip -> {
            try {
                MetadataMap authBeginMap = cip170MetadataFactory.authBeginMap(
                        authBegin.getAid(),
                        authBegin.getLeafSchemaSaid(),
                        authBegin.getReducedCesrChain(),
                        null,
                        authBegin.authorizedLabelsAsList());

                Metadata metadata = MetadataBuilder.createMetadata();
                metadata.put(CIP170_METADATA_LABEL, authBeginMap);

                byte[] serialisedTxBytes = serialiseTransaction(metadata);

                return Either.right(new API3BlockchainTransaction(chainTip.getAbsoluteSlot(), serialisedTxBytes,
                        organiserWallet.baseAddress()));
            } catch (Exception e) {
                log.error("Error building the AUTH_BEGIN transaction for ceremony:{}", authBegin.getId(), e);

                ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Error building the AUTH_BEGIN transaction: %s".formatted(e.getMessage()));
                problemDetail.setTitle("ERROR_BUILDING_AUTH_BEGIN_TRANSACTION");

                return Either.left(problemDetail);
            }
        });
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
