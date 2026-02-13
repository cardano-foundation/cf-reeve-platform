/// usr/bin/env jbang "$0" "$@" ; exit $?
///
// @formatter:off
//JAVA 24+
//COMPILE_OPTIONS -source 24
//RUNTIME_OPTIONS

//REPOS snapshots=https://central.sonatype.com/repository/maven-snapshots/
//REPOS central=https://repo.maven.apache.org/maven2
//DEPS org.cardanofoundation:signify:0.1.2-ebfb904-SNAPSHOT
//DEPS com.bloxbean.cardano:cardano-client-lib:0.7.1
//DEPS com.bloxbean.cardano:cardano-client-backend-blockfrost:0.7.0-beta2
//SOURCES KeriUtils.java
// @formatter:on

import java.math.BigInteger;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.cardanofoundation.signify.app.Contacting;
import org.cardanofoundation.signify.app.Exchanging;
import org.cardanofoundation.signify.app.Notifying;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Operation;
import org.cardanofoundation.signify.app.credentialing.credentials.CredentialFilter;
import org.cardanofoundation.signify.app.credentialing.ipex.IpexAdmitArgs;
import org.cardanofoundation.signify.app.aiding.EventResult;
import org.cardanofoundation.signify.cesr.args.RawArgs;
import org.cardanofoundation.signify.cesr.Diger;
import org.cardanofoundation.signify.cesr.exceptions.LibsodiumException;
import org.cardanofoundation.signify.cesr.util.CESRStreamUtil;
import org.cardanofoundation.signify.cesr.util.Utils;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.backend.model.metadata.MetadataJSONContent;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.metadata.Metadata;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataList;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.quicktx.TxResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public class AttestTransaction {
    private static final String IDENTIFIER_NAME = "GTReeveClient";

    private static final String VLEI_CARDANO_METADATA_SIGNER_SCHEMA_SAID = "EKU2UWx115nPv1JqWVMCFRn0_EMaME08HrUK5cLuTP89";

    public static final String KERI_URL = "https://keria.cardano-foundation.app.reeve.technology";
    public static final String KERI_BOOT_URL = "https://keria-boot.cardano-foundation.app.reeve.technology";

    private static final String passcode = System.getenv().getOrDefault("PASSCODE", "");
    private static final String txHashToAttest = System.getenv().getOrDefault("TX_HASH_TO_ATTEST", "");

    // Wallet specific constants
    private static final String mnemonic = System.getenv().getOrDefault("MNEMONIC", "");
    private static final String NETWORK_TYPE = System.getenv().getOrDefault("NETWORK", "mainnet");
    private static final Network network = getNetwork();
    private static final String blockfrostProjectId = System.getenv().getOrDefault("BLOCKFROST_PROJECT_ID", "");

    private static final BackendService backendService = createBackendService();
    private static final QuickTxBuilder QuickTxBuilder = new QuickTxBuilder(backendService);

    private static Network getNetwork() {
        return switch (NETWORK_TYPE.toLowerCase()) {
            case "mainnet" -> Networks.mainnet();
            case "preprod" -> Networks.preprod();
            default -> Networks.testnet(); // preview is the default
        };
    }

    private static BackendService createBackendService() {
        String baseUrl = switch (NETWORK_TYPE.toLowerCase()) {
            case "mainnet" -> "https://cardano-mainnet.blockfrost.io/api/v0/";
            case "preprod" -> "https://cardano-preprod.blockfrost.io/api/v0/";
            default -> "https://cardano-preview.blockfrost.io/api/v0/";
        };
        return new BFBackendService(baseUrl, blockfrostProjectId);
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> requiredEnvVars = Map.of(
                "PASSCODE", passcode,
                "MNEMONIC", mnemonic,
                "BLOCKFROST_PROJECT_ID", blockfrostProjectId,
                "TX_HASH_TO_ATTEST", txHashToAttest
        );

        requiredEnvVars.forEach((name, value) -> {
            if (value == null || value.isEmpty()) {
                System.err.println("ERROR: " + name + " environment variable is required.");
                System.exit(1);
            }
        });

        // --- SETUP CLIENT WITH EXISTING IDENTIFIER --- //
        SignifyClient client = KeriUtils.connectClient(KERI_URL, KERI_BOOT_URL, passcode);
        KeriUtils.Aid aid = KeriUtils.getExistingAid(client, IDENTIFIER_NAME);
        
        if (aid == null) {
            System.err.println("ERROR: Identifier '" + IDENTIFIER_NAME + "' not found!");
            System.exit(1);
        }

        System.out.println("Successfully connected to identifier:");
        System.out.println("  AID: " + aid.prefix());
        System.out.println("  OOBI: " + aid.oobi());
        System.out.println();

        List<Map<String, Object>> existingCredentials = findIssuedCredential(client, aid.prefix(), VLEI_CARDANO_METADATA_SIGNER_SCHEMA_SAID);
        if (existingCredentials.size() == 0) {
            System.out.println("No existing vLEI Cardano Metadata credential was found. Please ensure this signer has the authority to attest to transactions.");
            System.exit(1);
        }

        Result<List<MetadataJSONContent>> jsonMetadataByTxnHash = backendService.getMetadataService()
                .getJSONMetadataByTxnHash(txHashToAttest);

        if (!jsonMetadataByTxnHash.isSuccessful()) {
            System.out.println("Provided Crdano transaction hash does not exist on this network.");
            System.exit(1);
        }

        MetadataJSONContent jsonContent = jsonMetadataByTxnHash.getValue().stream().filter(json -> json.getLabel().equals("1447")).findFirst().orElseThrow(
                () -> new IllegalStateException("Provided Cardano transaction is not a valid Reeve transaction to attest.")
        );
        String jsonString = jsonContent.getJsonMetadata().toString();

        System.out.print("About to attest to the following metadata:\n\n" + jsonString + "\n\nContinue? (yes/no): ");
        String response = System.console() != null ? System.console().readLine() : new java.util.Scanner(System.in).nextLine();

        if (response == null || !(response.equalsIgnoreCase("yes") || response.equalsIgnoreCase("y"))) {
            System.out.println("Skipping attestation.");
            System.exit(1);
        }

        MetadataMap reeveMetadataMap = MetadataBuilder.metadataMapFromJsonBody(jsonString);

        Diger diger = new Diger(new RawArgs(), CborSerializationUtil.serialize(reeveMetadataMap.getMap()));
        String qb64 = diger.getQb64();

        Map<String, String> seal = new LinkedHashMap<>();
        seal.put("d", qb64);

        EventResult result = client.identifiers().interact(IDENTIFIER_NAME, seal);
        var op = client.operations().wait(Operation.fromObject(result.op()));
        var opResponse = (Map<String, Object>) op.getResponse();

        System.out.println("Transaction attested. Publishing on-chain.");
        buildTransaction(aid.prefix(), (String) opResponse.get("s"), qb64, reeveMetadataMap);
    }

    private static List<Map<String, Object>> findIssuedCredential(SignifyClient client, String holderAid, String schemaSaid) {
        try {
            Map<String, Object> filterData = new LinkedHashMap<>();
            CredentialFilter credentialFilter = CredentialFilter.builder().build();
            credentialFilter.setFilter(filterData);
            filterData.put("-s", schemaSaid);
            filterData.put("-a-i", holderAid);
            List<Map<String, Object>> list = castObjectToListMap(client.credentials().list(credentialFilter));
            return list;

        } catch (Exception e) {
            System.out.println("Error checking for existing credential: " + e.getMessage());
        }

        return new ArrayList<>();
    }

    static void buildTransaction(String aid, String sn, String digest, MetadataMap reeveMetadataMap) {
        Account account = Account.createFromMnemonic(network, mnemonic);

        MetadataMap metadataMap = MetadataBuilder.createMap();
        metadataMap.put("t", "ATTEST");
        metadataMap.put("s", sn);
        metadataMap.put("i", aid);
        metadataMap.put("d", digest);
        MetadataMap v = MetadataBuilder.createMap();
        v.put("v", "1.0");
        metadataMap.put("v", v);

        Metadata metadata = MetadataBuilder.createMetadata();
        metadata.put(170, metadataMap);
        metadata.put(1447, reeveMetadataMap);

        Tx tx = new Tx()
                .payToAddress(account.baseAddress(), Amount.ada(2))
                .from(account.baseAddress())
                .attachMetadata(metadata);
        TxResult txResult = QuickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(account))
                .feePayer(account.baseAddress())
                .completeAndWait();
        System.out.println("txResult: " + txResult);
        System.out.println("Transaction submitted. Tx Hash: " + txResult.getTxHash());
    }

    public static List<Map<String, Object>> castObjectToListMap(Object object) {
        return (List<Map<String, Object>>) object;
    }
}
