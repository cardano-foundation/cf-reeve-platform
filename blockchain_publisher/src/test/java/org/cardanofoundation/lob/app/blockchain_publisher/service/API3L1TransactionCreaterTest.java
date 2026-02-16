package org.cardanofoundation.lob.app.blockchain_publisher.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Field;
import java.util.Optional;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.backend.api.BackendService;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.blockchain_common.service_assistance.MetadataChecker;
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

}
