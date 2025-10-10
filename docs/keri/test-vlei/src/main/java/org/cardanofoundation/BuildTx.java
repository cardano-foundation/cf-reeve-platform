package org.cardanofoundation;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import org.cardanofoundation.domain.CredentialSerializationData;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
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

        Map<String, Object> credentialMap = Map.of(
            "type", "CREDENTIAL",
            "i", data.prefix().getLast(),
            "vcp", Map.of(
                "d", data.vcp().events(),
                "a", data.vcp().attachements()
            ),
            "iss", Map.of(
                "d", data.iss().events(),
                "a", data.iss().attachements()
            ),
            "acdc", data.acdc()
        );

        MetadataMap metadataMap = MetadataBuilder.createMap();
        metadataMap.put("type", "CREDENTIAL");
        metadataMap.put("i", data.prefix().getLast());
        MetadataMap vcpMap = MetadataBuilder.createMap();
        vcpMap.put("a", MetadataBuilder.createList().addAll(data.vcp().attachements().toArray(new String[0])));
        metadataMap.put("vcp", vcpMap);
        MetadataMap issMap = MetadataBuilder.createMap();
        issMap.put("a", MetadataBuilder.createList().addAll(data.iss().attachements().toArray(new String[0])));
        metadataMap.put("iss", issMap);
        
        Metadata metadata = MetadataBuilder.createMetadata();
        CBORMetadataMap cborMetadataMap = new CBORMetadataMap(metadataMap.getMap());
        metadata.put(METADATA_LABEL, cborMetadataMap);
        Tx tx = new Tx()
            .payToAddress(owner.baseAddress(), Amount.ada(2))
            .attachMetadata(metadata)
            .from(owner.baseAddress());
        quickTxBuilder.compose(tx)
            .withSigner(SignerProviders.signerFrom(owner))
            .feePayer(owner.baseAddress())
            .completeAndWait();
    }
    
}
