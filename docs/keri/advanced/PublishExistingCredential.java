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
//SOURCES ../KeriUtils.java
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
import org.cardanofoundation.signify.cesr.exceptions.LibsodiumException;
import org.cardanofoundation.signify.cesr.util.CESRStreamUtil;
import org.cardanofoundation.signify.cesr.util.Utils;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
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

public class PublishExistingCredential {
    private static final String IDENTIFIER_NAME = "GTReeveClient";
    
    public static final String QVI_SCHEMA_SAID = "EBfdlu8R27Fbx-ehrqwImnK-8Cm79sqbAQ4MmvEAYqao";
    public static final String LE_SCHEMA_SAID = "ENPXp1vQzRF6JwIuS-mp2U8Uf1MoADoP_GqQ62VsDZWY";
    private static final String VLEI_CARDANO_METADATA_SIGNER_SCHEMA_SAID = "EKU2UWx115nPv1JqWVMCFRn0_EMaME08HrUK5cLuTP89";

    public static final String SCHEMA_SERVER_URL = "https://cred-issuance.demo.idw-sandboxes.cf-deployments.org/oobi";
    public static final String KERI_URL = "https://keria.cardano-foundation.app.reeve.technology";
    public static final String KERI_BOOT_URL = "https://keria-boot.cardano-foundation.app.reeve.technology";
    
    public static final String VLEI_CARDANO_METADATA_SIGNER_SCHEMA_URL = SCHEMA_SERVER_URL + "/" + VLEI_CARDANO_METADATA_SIGNER_SCHEMA_SAID;
    public static final String LE_SCHEMA_URL = SCHEMA_SERVER_URL + "/" + LE_SCHEMA_SAID;
    public static final String QVI_SCHEMA_URL = SCHEMA_SERVER_URL + "/" + QVI_SCHEMA_SAID;

    private static final String passcode = System.getenv().getOrDefault("PASSCODE", "");
    private static final String LEI = System.getenv().getOrDefault("LEI", "");

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
                "LEI", LEI,
                "MNEMONIC", mnemonic,
                "BLOCKFROST_PROJECT_ID", blockfrostProjectId
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

        List<String> schemaUrls = List.of(QVI_SCHEMA_URL, LE_SCHEMA_URL, VLEI_CARDANO_METADATA_SIGNER_SCHEMA_URL);
        resolveSchemas(client, schemaUrls);

        List<Map<String, Object>> existingCredentials = findIssuedCredential(client, aid.prefix(), VLEI_CARDANO_METADATA_SIGNER_SCHEMA_SAID);
        if (existingCredentials.size() == 0) {
            System.out.println("No existing credential found.");
            System.exit(1);
        }

        System.out.print("\nExisting credential found. Do you want to push the existing credential to the blockchain? (yes/no): ");
        String response = System.console() != null ? System.console().readLine() : new java.util.Scanner(System.in).nextLine();

        if (response == null || !(response.equalsIgnoreCase("yes") || response.equalsIgnoreCase("y"))) {
            System.out.println("Skipping blockchain push for existing credential.");
            System.exit(1);
        }

        Map<String, Object> firstCred = existingCredentials.get(0);
        Object sadObj = firstCred.get("sad");

        String existingCredId;
        if (sadObj instanceof Map) {
            existingCredId = (String) ((Map<String, Object>) sadObj).get("d");
        } else {
            existingCredId = (String) sadObj;
        }

        // Re-fetch as we need the full CESR stream, not JSON variant
        Optional<String> credential = client.credentials().get(existingCredId);
        if (credential.isPresent()) {
            List<Map<String, Object>> cesrData = CESRStreamUtil.parseCESRData(credential.get());
            String stripped = strip(cesrData);

            buildTransaction(aid.prefix(), stripped.getBytes(), VLEI_CARDANO_METADATA_SIGNER_SCHEMA_SAID, LEI);
        } else {
            System.err.println("ERROR: Could not retrieve full credential data for ID: " + existingCredId);
        }
    }

    static String strip(List<Map<String, Object>> cesrData) {
        List<Map<String, Object>> allVcpEvents = new ArrayList<>();
        List<String> allVcpAttachments = new ArrayList<>();
        List<Map<String, Object>> allIssEvents = new ArrayList<>();
        List<String> allIssAttachments = new ArrayList<>();
        List<Map<String, Object>> allAcdcEvents = new ArrayList<>();
        List<String> allAcdcAttachments = new ArrayList<>();

        for (Map<String, Object> eventData : cesrData) {
            Map<String, Object> event = (Map<String, Object>) eventData.get("event");

            // Check for event type
            Object eventTypeObj = event.get("t");
            if (eventTypeObj != null) {
                String eventType = eventTypeObj.toString();
                switch (eventType) {
                    case "vcp":
                        allVcpEvents.add(event);
                        allVcpAttachments.add((String) eventData.get("atc"));
                        break;
                    case "iss":
                        allIssEvents.add(event);
                        allIssAttachments.add((String) eventData.get("atc"));
                        break;
                }
            } else {
                // Check if this is an ACDC (credential data) without "t" field
                if (event.containsKey("s") && event.containsKey("a") && event.containsKey("i")) {
                    Object schemaObj = event.get("s");
                    if (schemaObj != null) {
                        allAcdcEvents.add(event);
                        allAcdcAttachments.add("");
                    }
                }
            }
        }

        List<Map<String, Object>> combinedEvents = new ArrayList<>();
        List<String> combinedAttachments = new ArrayList<>();

        combinedEvents.addAll(allVcpEvents);
        combinedEvents.addAll(allIssEvents);
        combinedEvents.addAll(allAcdcEvents);

        combinedAttachments.addAll(allVcpAttachments);
        combinedAttachments.addAll(allIssAttachments);
        combinedAttachments.addAll(allAcdcAttachments);

        return CESRStreamUtil.makeCESRStream(combinedEvents, combinedAttachments);
    }

    static void buildTransaction(String aid, byte[] credentialChain, String saidOfLeafCredentialSchema, String lei) {
        Account account = Account.createFromMnemonic(network, mnemonic);

        byte[][] chunks = splitIntoChunks(credentialChain, 64);
        MetadataList credentialChunks = MetadataBuilder.createList();
        for (byte[] chunk : chunks) {
            credentialChunks.add(chunk);
        }

        MetadataMap metadataMap = MetadataBuilder.createMap();
        metadataMap.put("t", "AUTH_BEGIN");
        metadataMap.put("s", saidOfLeafCredentialSchema);
        metadataMap.put("i", aid);
        metadataMap.put("c", credentialChunks);
        MetadataMap v = MetadataBuilder.createMap();
        v.put("v", "1.0");
        v.put("k", "KERI10");
        v.put("a", "ACDC10");
        metadataMap.put("v", v);
        MetadataMap m = MetadataBuilder.createMap();
        MetadataList l = MetadataBuilder.createList();
        l.add(BigInteger.valueOf(1447));
        m.put("l", l);
        m.put("LEI", lei);
        metadataMap.put("m", m);

        Metadata metadata = MetadataBuilder.createMetadata();
        metadata.put(170, metadataMap);

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

    // ------- Helper Methods ------- //

    private static class Notification {
        public String i;
        public String dt;
        public boolean r;
        public NotificationAction a;

        public static class NotificationAction {
            public String r;
            public String d;
            public String m;
        }
    }

    public static byte[][] splitIntoChunks(byte[] data, int chunkSize) {
        int numChunks = (data.length + chunkSize - 1) / chunkSize; // ceiling division
        byte[][] chunks = new byte[numChunks][];

        for (int i = 0; i < numChunks; i++) {
            int start = i * chunkSize;
            int end = Math.min(start + chunkSize, data.length);
            chunks[i] = Arrays.copyOfRange(data, start, end);
        }

        return chunks;
    }

    private static void resolveSchemas(SignifyClient client, List<String> schemaUrls) {
        for (String schemaUrl : schemaUrls) {
            try {
                Object result = client.oobis().resolve(schemaUrl, null);
                client.operations().wait(Operation.fromObject(result));
            } catch (LibsodiumException | IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private static List<Notification> waitForNotifications(String route,
            SignifyClient client, KeriUtils.Aid aid) throws IOException, InterruptedException {
        int waitTimeMs = 3000;

        System.out.println("Waiting to be issued a credential...");
        System.out.println("Receiver client prefix: " + aid.prefix());

        while (true) {
            System.out.println("...");

            try {
                Notifying.Notifications.NotificationListResponse response =
                        client.notifications().list();
                String notesResponse = response.notes();

                List<Notification> receiverNotifications =
                        Utils.fromJson(notesResponse, new TypeReference<>() {});

                List<Notification> filteredNotifications =
                        receiverNotifications.stream().filter(note -> {
                            // Check if notification has not been read yet (r field should be false)
                            boolean isUnread = !Boolean.TRUE.equals(note.r);
                            // Check if route matches
                            boolean routeMatches = note.a != null && route.equals(note.a.r);

                            return isUnread && routeMatches;
                        }).toList();

                if (filteredNotifications.size() > 0) {
                    return filteredNotifications;
                }
            } catch (Exception e) {
                System.out.println("Error retrieving notifications: " + e.getMessage());
                e.printStackTrace();
            }
        }
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

    /**
     * Wait for credential to be available in the credential store
     * The credential may not be immediately available after admit process completes
     */
    private static Optional<String> waitForCredential(SignifyClient client, String credentialId) 
            throws InterruptedException {
        int maxAttempts = 10;
        int waitTimeMs = 2000;
        
        System.out.println("Waiting for credential to be available in store...");
        
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Optional<String> credential = client.credentials().get(credentialId);
                if (credential.isPresent()) {
                    System.out.println("Credential retrieved successfully!");
                    return credential;
                }
            } catch (Exception e) {
                System.out.println("Attempt " + attempt + " failed: " + e.getMessage());
            }
            
            if (attempt < maxAttempts) {
                System.out.println("Credential not yet available, waiting... (attempt " + attempt + "/" + maxAttempts + ")");
                Thread.sleep(waitTimeMs);
            }
        }
        
        return Optional.empty();
    }

    /**
     * Build IPEX admit arguments
     */
    private static IpexAdmitArgs buildAdmitArgs(String senderName, String grantSaid,
            String recipient) {
        String dt = new Date().toInstant().toString().replace("Z", "000+00:00");

        IpexAdmitArgs args = IpexAdmitArgs.builder().build();
        args.setSenderName(senderName);
        args.setMessage("");
        args.setGrantSaid(grantSaid);
        args.setRecipient(recipient);
        args.setDatetime(dt);

        return args;
    }

    private static void executeAdmitProcess(SignifyClient client, KeriUtils.Aid receiver, String issuerAid,
            IpexAdmitArgs admitArgs, String notificationId) throws Exception {
        // Create and submit admit
        Exchanging.ExchangeMessageResult admitResult = client.ipex().admit(admitArgs);
        Object operation = client.ipex().submitAdmit(receiver.name(),
                admitResult.exn(), admitResult.sigs(), admitResult.atc(),
                Collections.singletonList(issuerAid));

        // Wait for operation completion
        Operation<Object> waitOp =
                client.operations().wait(Operation.fromObject(operation));
        if (!waitOp.isDone() || waitOp.getError() != null) {
            throw new IllegalStateException("IPEX admit operation failed: "
                    + (waitOp.getError() != null ? waitOp.getError() : "unknown"));
        }

        // Mark notification as read and cleanup
        client.notifications().mark(notificationId);
        client.operations().delete(waitOp.getName());
    }

    public static List<Map<String, Object>> castObjectToListMap(Object object) {
        return (List<Map<String, Object>>) object;
    }

    public static LinkedHashMap<String, Object> castObjectToLinkedHashMap(Object object) {
        return (LinkedHashMap<String, Object>) object;
    }
}
