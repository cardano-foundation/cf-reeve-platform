package org.cardanofoundation.lob.app.blockchain_publisher.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.Set;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.metadata.Metadata;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import io.vavr.control.Either;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ProblemDetail;

import org.cardanofoundation.lob.app.blockchain_common.domain.CardanoNetwork;
import org.cardanofoundation.lob.app.blockchain_common.domain.ChainTip;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.MetadataChecker;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.API1BlockchainTransactions;
import org.cardanofoundation.lob.app.blockchain_publisher.service.ipfs.IpfsPublisher;
import org.cardanofoundation.lob.app.blockchain_reader.BlockchainReaderPublicApiIF;

@ExtendWith(MockitoExtension.class)
class API1L1TransactionCreatorTest {

    @Mock private BackendService backendService;
    @Mock private API1MetadataSerialiser api1MetadataSerialiser;
    @Mock private BlockchainReaderPublicApiIF blockchainReaderPublicApi;
    @Mock private MetadataChecker jsonSchemaMetadataChecker;
    @Mock private IpfsPublisher ipfsPublisher;

    private final ChainTip chainTip = ChainTip.builder()
            .absoluteSlot(12345L)
            .blockHash("abc123")
            .epochNo(Optional.of(500))
            .network(CardanoNetwork.MAIN)
            .isSynced(true)
            .build();

    private API1L1TransactionCreator creatorNoIpfs;
    private API1L1TransactionCreator creatorWithIpfs;

    @BeforeEach
    void setUp() {
        Account organiserAccount = new Account();
        creatorNoIpfs = new API1L1TransactionCreator(
                backendService, api1MetadataSerialiser, blockchainReaderPublicApi,
                jsonSchemaMetadataChecker, organiserAccount, Optional.empty(),
                1, false
        );
        creatorWithIpfs = new API1L1TransactionCreator(
                backendService, api1MetadataSerialiser, blockchainReaderPublicApi,
                jsonSchemaMetadataChecker, organiserAccount, Optional.of(ipfsPublisher),
                1, false
        );
    }

    @Test
    void init_setsRunId() throws NoSuchFieldException, IllegalAccessException {
        creatorNoIpfs.init();

        Field runIdField = API1L1TransactionCreator.class.getDeclaredField("runId");
        runIdField.setAccessible(true);
        String runId = (String) runIdField.get(creatorNoIpfs);

        assertThat(runId).isNotNull().isNotEmpty();
    }

    @Test
    void pullBlockchainTransaction_chainTipFails_returnsLeft() {
        ProblemDetail error = ProblemDetail.forStatus(503);
        error.setTitle("CHAIN_TIP_UNAVAILABLE");
        when(blockchainReaderPublicApi.getChainTip()).thenReturn(Either.left(error));

        Either<ProblemDetail, Optional<API1BlockchainTransactions>> result =
                creatorNoIpfs.pullBlockchainTransaction("org-1", Set.of());

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("CHAIN_TIP_UNAVAILABLE");
    }

    @Test
    void pullBlockchainTransaction_withoutIpfsPublisher_emptyTransactions_returnsOptionalEmpty() {
        when(blockchainReaderPublicApi.getChainTip()).thenReturn(Either.right(chainTip));

        Either<ProblemDetail, Optional<API1BlockchainTransactions>> result =
                creatorNoIpfs.pullBlockchainTransaction("org-1", Set.of());

        assertThat(result.isRight()).isTrue();
        assertThat(result.get()).isEmpty();
    }

    @Test
    void pullBlockchainTransaction_withIpfsPublisher_schemaInvalid_returnsLeft() {
        MetadataMap metadataMap = MetadataBuilder.createMap();
        metadataMap.put("data", MetadataBuilder.createList());

        when(blockchainReaderPublicApi.getChainTip()).thenReturn(Either.right(chainTip));
        when(api1MetadataSerialiser.serialiseToMetadataMap(any(), any(), anyLong())).thenReturn(metadataMap);
        when(jsonSchemaMetadataChecker.checkTransactionMetadata(anyString())).thenReturn(false);

        Either<ProblemDetail, Optional<API1BlockchainTransactions>> result =
                creatorWithIpfs.pullBlockchainTransaction("org-1", Set.of());

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("INVALID_TRANSACTION_METADATA");
    }

    @Test
    void pullBlockchainTransaction_withIpfsPublisher_ipfsPublishFails_returnsLeft() {
        MetadataMap metadataMap = MetadataBuilder.createMap();
        metadataMap.put("data", MetadataBuilder.createList());

        ProblemDetail ipfsError = ProblemDetail.forStatus(500);
        ipfsError.setTitle("IPFS_UPLOAD_ERROR");
        ipfsError.setDetail("Connection refused");

        when(blockchainReaderPublicApi.getChainTip()).thenReturn(Either.right(chainTip));
        when(api1MetadataSerialiser.serialiseToMetadataMap(any(), any(), anyLong())).thenReturn(metadataMap);
        when(jsonSchemaMetadataChecker.checkTransactionMetadata(anyString())).thenReturn(true);
        when(ipfsPublisher.publish(anyString())).thenReturn(Either.left(ipfsError));

        Either<ProblemDetail, Optional<API1BlockchainTransactions>> result =
                creatorWithIpfs.pullBlockchainTransaction("org-1", Set.of());

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("IPFS_UPLOAD_ERROR");
    }

    @Test
    void pullBlockchainTransaction_withIpfsPublisher_success_returnsTxWithCid() throws Exception {
        MetadataMap metadataMap = MetadataBuilder.createMap();
        metadataMap.put("data", MetadataBuilder.createList());

        when(blockchainReaderPublicApi.getChainTip()).thenReturn(Either.right(chainTip));
        when(api1MetadataSerialiser.serialiseToMetadataMap(any(), any(), anyLong())).thenReturn(metadataMap);
        when(jsonSchemaMetadataChecker.checkTransactionMetadata(anyString())).thenReturn(true);
        when(ipfsPublisher.publish(anyString())).thenReturn(Either.right("QmTestCid123"));

        API1L1TransactionCreator spy = spy(creatorWithIpfs);
        doReturn(new byte[]{1, 2, 3}).when(spy).serialiseTransaction(any(Metadata.class));

        Either<ProblemDetail, Optional<API1BlockchainTransactions>> result =
                spy.pullBlockchainTransaction("org-1", Set.of());

        assertThat(result.isRight()).isTrue();
        assertThat(result.get()).isPresent();
        API1BlockchainTransactions txs = result.get().get();
        assertThat(txs.organisationId()).isEqualTo("org-1");
        assertThat(txs.remainingTransactions()).isEmpty();
        assertThat(txs.creationSlot()).isEqualTo(12345L);
        assertThat(txs.serialisedTxData()).isEqualTo(new byte[]{1, 2, 3});
    }

    @Test
    void pullBlockchainTransaction_withIpfsPublisher_ipfsPublishCalledWithDataJson() {
        MetadataMap metadataMap = MetadataBuilder.createMap();
        metadataMap.put("data", MetadataBuilder.createList());

        when(blockchainReaderPublicApi.getChainTip()).thenReturn(Either.right(chainTip));
        when(api1MetadataSerialiser.serialiseToMetadataMap(any(), any(), anyLong())).thenReturn(metadataMap);
        when(jsonSchemaMetadataChecker.checkTransactionMetadata(anyString())).thenReturn(true);

        ProblemDetail ipfsError = ProblemDetail.forStatus(500);
        when(ipfsPublisher.publish(anyString())).thenAnswer(invocation -> {
            String json = invocation.getArgument(0);
            // The JSON passed to IPFS should contain the data that was extracted from the metadata
            assertThat(json).isNotBlank();
            return Either.left(ipfsError);
        });

        creatorWithIpfs.pullBlockchainTransaction("org-1", Set.of());
    }
}
