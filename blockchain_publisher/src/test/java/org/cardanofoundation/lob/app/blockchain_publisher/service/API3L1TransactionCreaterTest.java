package org.cardanofoundation.lob.app.blockchain_publisher.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Optional;

import co.nstant.in.cbor.model.Map;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import io.vavr.control.Either;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.blockchain_common.domain.ChainTip;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.MetadataChecker;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.API3BlockchainTransaction;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports.ReportEntity;
import org.cardanofoundation.lob.app.blockchain_reader.BlockchainReaderPublicApiIF;

@ExtendWith(MockitoExtension.class)
class API3L1TransactionCreaterTest {

    @Mock
    private BackendService backendService;
    @Mock
    private API3MetadataSerialiser api3MetadataSerialiser;
    @Mock
    private BlockchainReaderPublicApiIF blockchainReaderPublicApi;
    @Mock
    private MetadataChecker jsonSchemaMetadataChecker;
    @Mock
    private Optional<KeriService> keriService;

    private API3L1TransactionCreator api3L1TransactionCreator;

    @BeforeEach
    void setUp() {
        Account organiserAccount = new Account();
        api3L1TransactionCreator = new API3L1TransactionCreator(
            backendService,
            api3MetadataSerialiser,
            blockchainReaderPublicApi,
            jsonSchemaMetadataChecker,
                organiserAccount,
            3, // metadataLabel
            false, // debugStoreOutputTx
            false, // keriEnabled
            keriService,
            0 // keriMetadataLabel
        );
    }

    @Test
    void testInit() throws NoSuchFieldException, IllegalAccessException {
        api3L1TransactionCreator.init();

        Field runIdField = API3L1TransactionCreator.class.getDeclaredField("runId");
        runIdField.setAccessible(true);
        String runId = (String) runIdField.get(api3L1TransactionCreator);

        assertNotNull(runId);
        assertFalse(runId.isEmpty());
    }

    @Test
    void pullBlockchainTransaction_chainTipProblem() {
        ReportEntity reportEntity = mock(ReportEntity.class);
        Problem problem = mock(Problem.class);
        when(blockchainReaderPublicApi.getChainTip()).thenReturn(Either.left(problem));

        Either<Problem, API3BlockchainTransaction> response = api3L1TransactionCreator.pullBlockchainTransaction(reportEntity);
        assertTrue(response.isLeft());
    }

    @Test
    void pullBlockchainTransaction_serializationError() {
        ReportEntity reportEntity = mock(ReportEntity.class);
        ChainTip chainTip = mock(ChainTip.class);
        MetadataMap metadataMap = mock(MetadataMap.class);
        when(blockchainReaderPublicApi.getChainTip()).thenReturn(Either.right(chainTip));
        when(chainTip.getAbsoluteSlot()).thenReturn(100L);
        when(api3MetadataSerialiser.serialiseToMetadataMap(reportEntity, chainTip.getAbsoluteSlot())).thenReturn(metadataMap);

        Either<Problem, API3BlockchainTransaction> response = api3L1TransactionCreator.pullBlockchainTransaction(reportEntity);
        assertTrue(response.isLeft());
    }

    @Test
    void pullBlockchainTransaction_invalidReportMetadata() {
        ReportEntity reportEntity = mock(ReportEntity.class);
        ChainTip chainTip = mock(ChainTip.class);
        MetadataMap metadataMap = mock(MetadataMap.class);
        Map cborMap = new Map();
        when(metadataMap.getMap()).thenReturn(cborMap);
        when(blockchainReaderPublicApi.getChainTip()).thenReturn(Either.right(chainTip));
        when(chainTip.getAbsoluteSlot()).thenReturn(100L);
        when(api3MetadataSerialiser.serialiseToMetadataMap(reportEntity, chainTip.getAbsoluteSlot())).thenReturn(metadataMap);

        Either<Problem, API3BlockchainTransaction> response = api3L1TransactionCreator.pullBlockchainTransaction(reportEntity);
        assertTrue(response.isLeft());
    }

}
