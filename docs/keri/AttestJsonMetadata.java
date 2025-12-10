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
    private static final String CLIENT_NAME = "GTReeveClient";
    private static final String IDENTIFIER_BRAN = "0ADF2TpptgqcDE5IQUF1H"; // TODO Need to randomized or passed in
    public static final String KERI_URL = "https://keria.staging.cardano-foundation.app.reeve.technology";
    public static final String KERI_BOOT_URL = "https://keria-boot.staging.cardano-foundation.app.reeve.technology";

    private static final String transactionHash = "313267a550a0ebc35dacf4d30c95fa71cdbe88349df2acc80d5579c3a20c9493";
    private static final String mnemonic = "test test test test test test test test test test test test test test test test test test test test test test test sauce";
    private static final Network network = Networks.mainnet();
    private static final BackendService backendService = new BFBackendService(
            "https://cardano-mainnet.blockfrost.io/api/v0/", "mainnetlD8Ml06QIX8KlTH9Oa4Ovvvl5hHv9xdn");
    private static final QuickTxBuilder quickTxBuilder = new QuickTxBuilder(backendService);

    public static void main(String[] args) throws Exception {
        Result<List<MetadataCBORContent>> cborMetadataByTxnHash = backendService.getMetadataService()
                .getCBORMetadataByTxnHash(transactionHash);
        List<MetadataCBORContent> value = cborMetadataByTxnHash.getValue();
        MetadataCBORContent first = value.getFirst();
        String cborString = first.getCborMetadata();
        SignifyClient client = getOrCreateClient(IDENTIFIER_BRAN);
        byte[] blake3_256 = CoreUtil.blake3_256(
                cborString.getBytes(), 32);
        Diger diger = new Diger(RawArgs.builder().raw(blake3_256).build());
        EventResult interact = client.identifiers().interact(CLIENT_NAME, cborMetadataByTxnHash);

        Optional<HabState> optional = client.identifiers().get(CLIENT_NAME);
        String prefix = optional.orElseThrow().getPrefix();

        Map<String, Object> ked = interact.serder().getKed();
        MetadataMap keriMetadataMap = MetadataBuilder.createMap();
        keriMetadataMap.put("t", "ATTEST");
        keriMetadataMap.put("i", prefix);
        keriMetadataMap.put("d", diger.getQb64());
        keriMetadataMap.put("s", ked.get("s").toString());
        MetadataMap v = MetadataBuilder.createMap();
        v.put("v", "1.0");
        keriMetadataMap.put("v", v);
        Metadata metadata = MetadataBuilder.createMetadata();
        metadata.put(170, keriMetadataMap);

        // Reeve Data
        Result<List<MetadataJSONContent>> jsonMetadataByTxnHash = backendService.getMetadataService()
                .getJSONMetadataByTxnHash(transactionHash);
        MetadataJSONContent jsonContent = jsonMetadataByTxnHash.getValue().getFirst();
        String asText = jsonContent.getJsonMetadata().asText();
        String prettyString = jsonContent.getJsonMetadata().toString();
        MetadataMap reeveMetadataMap = MetadataBuilder.metadataMapFromJsonBody(prettyString);

        metadata.put(1447, reeveMetadataMap);

        // Devnet Test
        BackendService devNetBackendService = new BFBackendService("http://localhost:8081/api/v1/", "Dummy Key");
        QuickTxBuilder devNetQuickTxBuilder = new QuickTxBuilder(devNetBackendService);

        Account devnetAccount = Account.createFromMnemonic(Networks.testnet(), mnemonic);
        Tx tx = new Tx().payToAddress(devnetAccount.baseAddress(), Amount.ada(2)).attachMetadata(metadata).from(devnetAccount.baseAddress());
        TxResult completeAndWait = devNetQuickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(devnetAccount)).feePayer(devnetAccount.baseAddress())
                .completeAndWait();
        System.out.println(completeAndWait);
        System.out.println("Test");
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
