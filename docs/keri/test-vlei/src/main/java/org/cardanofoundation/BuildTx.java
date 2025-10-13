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
        metadataMap.put("vcp", getMetadataMap(data.vcp()));
        metadataMap.put("iss", getMetadataMap(data.iss()));
        MetadataList acdcList = MetadataBuilder.createList();
        data.acdc().forEach(a -> {
            acdcList.add(toMetadataMap(a));
        });
        metadataMap.put("acdc", acdcList);
        metadataMap.put("prefix", MetadataBuilder.createList().addAll(data.prefix().toArray(new String[0])));
        
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

    private static MetadataMap getMetadataMap(EventDataAndAttachement eventDataAndAttachement) {
        MetadataMap metadataMap = MetadataBuilder.createMap();
        MetadataList eventsList = MetadataBuilder.createList();
        eventDataAndAttachement.events().forEach(event -> {
            eventsList.add(toMetadataMap(event));
        });
        metadataMap.put("events", eventsList);

        MetadataList attachementsList = MetadataBuilder.createList();
        eventDataAndAttachement.attachements().forEach(attachement -> {
            attachementsList.add(attachement);
        });
        metadataMap.put("attachements", attachementsList);
        return metadataMap;
    }

    // Write a function which converts a Map<String, Object> to MetadataMap recursively object can be a string, string array, map or list
    private static MetadataMap toMetadataMap(Map<String, Object> map) {
        MetadataMap metadataMap = MetadataBuilder.createMap();
        map.forEach((key, value) -> {
            if(value instanceof String) {
                metadataMap.put(key, (String) value);
            } else if(value instanceof String[]) {
                String[] arr = (String[]) value;
                MetadataList list = MetadataBuilder.createList();
                for(String s : arr) {
                    list.add(s);
                }
                metadataMap.put(key, list);
            } else if(value instanceof Map) {
                metadataMap.put(key, toMetadataMap((Map<String, Object>) value));
            } else if(value instanceof List) {
                MetadataList list = MetadataBuilder.createList();
                ((List<?>) value).forEach(item -> {
                    if(item instanceof String) {
                        list.add((String) item);
                    } else if(item instanceof Map) {
                        list.add(toMetadataMap((Map<String, Object>) item));
                    }
                });
                metadataMap.put(key, list);
            }
        });
        return metadataMap;
    }
}
