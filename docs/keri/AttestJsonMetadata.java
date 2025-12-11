import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.cardanofoundation.signify.app.aiding.EventResult;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Coring;
import org.cardanofoundation.signify.app.coring.Operation;
import org.cardanofoundation.signify.cesr.Diger;
import org.cardanofoundation.signify.cesr.Salter;
import org.cardanofoundation.signify.cesr.args.RawArgs;
import org.cardanofoundation.signify.cesr.util.CoreUtil;
import org.cardanofoundation.signify.core.States;
import org.cardanofoundation.signify.core.States.HabState;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.backend.model.metadata.MetadataCBORContent;
import com.bloxbean.cardano.client.backend.model.metadata.MetadataJSONContent;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.metadata.Metadata;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.quicktx.TxResult;

/// usr/bin/env jbang "" "$@" ; exit $? ///
// @formatter:off
//JAVA 24+
//COMPILE_OPTIONS -source 24
//RUNTIME_OPTIONS

//DEPS org.cardanofoundation:signify:0.1.3-af01d93-SNAPSHOT
//DEPS com.bloxbean.cardano:cardano-client-lib:0.7.0-beta2
//DEPS com.bloxbean.cardano:cardano-client-backend-blockfrost:0.7.0-beta2
// @formatter:on

public class AttestJsonMetadata {

    // ------- Constants ------- //
    // KERI Identifier values
    private static final String CLIENT_NAME = "GTReeveClient";
    private static final String IDENTIFIER_BRAN = "0ADF2TpptgqcDE5IQUF1H"; // TODO Need to randomized or passed in

    // Reeve KERI service URLs
    public static final String KERI_URL = "https://keria.staging.cardano-foundation.app.reeve.technology";
    public static final String KERI_BOOT_URL = "https://keria-boot.staging.cardano-foundation.app.reeve.technology";

    // Transaction Hash which contains the JSON metadata to be attested
    private static final String transactionHash = "81280d4b7a04f42a87c7873df55111fed440e8e5d30e42df444e9db873e4ed95";
    // Mnemonic which will be used to create the Account for submitting the transaction
    private static final String mnemonic = "test test test test test test test test test test test test test test test test test test test test test test test sauce";

    private static final Network network = Networks.mainnet();
    //     private static final Network network = Networks.testnet(); // User for testing in Devnet
    private static final BackendService backendService = new BFBackendService(
            "https://cardano-mainnet.blockfrost.io/api/v0/", "MAINNET_BLOCKFROST_API_KEY");
            //     "http://localhost:8081/api/v1/", "Dummy Key"); // Used for Devnet
    private static final QuickTxBuilder quickTxBuilder = new QuickTxBuilder(backendService);

    public static void main(String[] args) throws Exception {
        
        // ---- Preparing Metadata from on-chain transaction and KERI attestation ---- //

        // Retrieving the JSON metadata with the label 1447 from the specific transaction
        Result<List<MetadataJSONContent>> jsonMetadataByTxnHash = backendService.getMetadataService()
                .getJSONMetadataByTxnHash(transactionHash);
        MetadataJSONContent jsonContent = jsonMetadataByTxnHash.getValue().stream().filter(json -> json.getLabel().equals("1447")).findFirst().orElseThrow();
        String jsonString = jsonContent.getJsonMetadata().toString();
        MetadataMap reeveMetadataMap = MetadataBuilder.metadataMapFromJsonBody(jsonString);
        
        // Serializing the JSON metadata map to create a digest
        byte[] blake3_256 = CoreUtil.blake3_256(
                    CborSerializationUtil.serialize(reeveMetadataMap.getMap()), 32);
        Diger diger = new Diger(RawArgs.builder().raw(blake3_256).build());
        // This digest will be attested on-chain
        String qb64 = diger.getQb64();

        // Creating KERI attestation for the digest
        SignifyClient client = getOrCreateClient(IDENTIFIER_BRAN);
        EventResult interact = client.identifiers().interact(CLIENT_NAME, qb64);

        Optional<HabState> optional = client.identifiers().get(CLIENT_NAME);
        String prefix = optional.orElseThrow().getPrefix();

        // Building the metadata structure for on-chain attestation
        Map<String, Object> ked = interact.serder().getKed();
        MetadataMap keriMetadataMap = MetadataBuilder.createMap();
        keriMetadataMap.put("t", "ATTEST");
        keriMetadataMap.put("i", prefix);
        keriMetadataMap.put("d", qb64);
        keriMetadataMap.put("s", ked.get("s").toString());
        MetadataMap v = MetadataBuilder.createMap();
        v.put("v", "1.0");
        keriMetadataMap.put("v", v);
        Metadata metadata = MetadataBuilder.createMetadata();
        metadata.put(170, keriMetadataMap);
        // Putting the original JSON metadata under label 1447
        metadata.put(1447, reeveMetadataMap);

        // ---- Submitting the transaction with KERI attestation ---- //
        Account account = Account.createFromMnemonic(network, mnemonic);
        Tx tx = new Tx().payToAddress(account.baseAddress(), Amount.ada(2)).attachMetadata(metadata).from(account.baseAddress());
        TxResult completeAndWait = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(account)).feePayer(account.baseAddress())
                .completeAndWait();
        if(!completeAndWait.isSuccessful()) {
            throw new RuntimeException("Transaction failed: " + completeAndWait);
        } else {
            System.out.println("Transaction successful. TxHash: " + completeAndWait.getTxHash());
        }
    }

    // Helper methods can be added here
    public static SignifyClient getOrCreateClient(String bran) throws Exception {

        if (bran == null || bran.isEmpty()) {
            bran = Coring.randomPasscode();
        }

        SignifyClient client = new SignifyClient(
                KERI_URL, bran, Salter.Tier.low, KERI_BOOT_URL, null);
        try {
            client.connect();
        } catch (Exception e) {
            client.boot();
            client.connect();
        }
        System.out.println("Client: "
                + Map.of("agent", client.getAgent() != null ? client.getAgent().getPre() : null,
                        "controller", client.getController().getPre()));
        return client;
    }

}
