package org.cardanofoundation;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.cardanofoundation.domain.CredentialSerializationData;
import org.cardanofoundation.domain.EventDataAndAttachement;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.backend.blockfrost.common.Constants;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.metadata.Metadata;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataList;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadataMap;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.quicktx.TxResult;
import com.fasterxml.jackson.databind.ObjectMapper;

public class BuildTx {

    static ObjectMapper objectMapper = new ObjectMapper();
    static BackendService backendService = new BFBackendService("http://localhost:8081/api/v1/", "Dummy Key");
    static UtxoSupplier utxoSupplier = new DefaultUtxoSupplier(backendService.getUtxoService());
    static QuickTxBuilder quickTxBuilder = new QuickTxBuilder(backendService);
        // Dummy mnemonic for the example. Replace with a valid mnemonic.
    static String mnemonic = "test test test test test test test test test test test test test test test test test test test test test test test sauce";

    // The network used for this example is Testnet
    static Network network = Networks.testnet();

    static Account owner = Account.createFromMnemonic(network, mnemonic);

    private static final int METADATA_LABEL = 1;

    public static void buildAndPublishTx(String fileName) {
        CredentialSerializationData data = null;
        try {
            data = objectMapper.readValue(new File(fileName), CredentialSerializationData.class);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            System.exit(1);
        }

        MetadataMap metadataMap = MetadataBuilder.createMap();
        metadataMap.put("t", "AUTH_BEGIN");
        metadataMap.put("i", data.aidOfSigner());
        metadataMap.put("s", data.saidOfLeafCredentialSchema());
        
        metadataMap.put("c", data.data()); // TODO need to add proper serialization here
        // adding the reeve metadata label to the transaction
        MetadataMap metadataMappingMap = MetadataBuilder.createMap();
        MetadataList list = MetadataBuilder.createList();
        metadataMappingMap.put("l", list.add("1447"));
        metadataMappingMap.put("LEI", org.cardanofoundation.utils.Constants.CFLEI);
        metadataMap.put("m", metadataMappingMap);
        
        Metadata metadata = MetadataBuilder.createMetadata();
        CBORMetadataMap cborMetadataMap = new CBORMetadataMap(metadataMap.getMap());
        metadata.put(METADATA_LABEL, cborMetadataMap);
        Tx tx = new Tx()
            .payToAddress(owner.baseAddress(), Amount.ada(2))
            .attachMetadata(metadata)
            .from(owner.baseAddress());
        TxResult completeAndWait = quickTxBuilder.compose(tx)
            .withSigner(SignerProviders.signerFrom(owner))
            .feePayer(owner.baseAddress())
            .completeAndWait();

        System.out.println("Transaction completed. TxId: " + completeAndWait);
    }
}
