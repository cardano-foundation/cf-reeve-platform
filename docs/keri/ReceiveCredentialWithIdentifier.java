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
import org.cardanofoundation.signify.app.credentialing.ipex.IpexAdmitArgs;
import org.cardanofoundation.signify.app.credentialing.registries.CreateRegistryArgs;
import org.cardanofoundation.signify.app.credentialing.registries.RegistryResult;
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

public class ReceiveCredentialWithIdentifier {
    private static final String REGISTRY_NAME = "CredentialRegistry";
    private static final String IDENTIFIER_NAME = "GTReeveClient";

    public static final String QVI_SCHEMA_SAID = "EBfdlu8R27Fbx-ehrqwImnK-8Cm79sqbAQ4MmvEAYqao";
    public static final String LE_SCHEMA_SAID = "ENPXp1vQzRF6JwIuS-mp2U8Uf1MoADoP_GqQ62VsDZWY";
    private static final String VLEI_CARDANO_METADATA_SIGNER_SCHEMA_SAID = "EKU2UWx115nPv1JqWVMCFRn0_EMaME08HrUK5cLuTP89";

    public static final String SCHEMA_SERVER_URL = "https://cred-issuance.demo.idw-sandboxes.cf-deployments.org/oobi";
    public static final String KERI_URL = "https://keria.staging.cardano-foundation.app.reeve.technology";
    public static final String KERI_BOOT_URL = "https://keria-boot.staging.cardano-foundation.app.reeve.technology";
    
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

        RegistryResult registry = createRegistry(client, aid, REGISTRY_NAME);

        List<String> schemaUrls = List.of(QVI_SCHEMA_URL, LE_SCHEMA_URL, VLEI_CARDANO_METADATA_SIGNER_SCHEMA_URL);
        resolveSchemas(client, schemaUrls);

        // ------- IPEX ADMIT ------- //
        List<Notification> notifications = waitForNotifications("/exn/ipex/grant", client, aid);
        if (notifications == null || notifications.isEmpty()) {
            throw new IllegalStateException("No grant notifications received");
        }

        System.out.println("Received credential grant notification!");

        LinkedHashMap<String, Object> map = (LinkedHashMap<String, Object>) client.exchanges().get(notifications.get(0).a.d).get();
        LinkedHashMap<String, Object> exn = (LinkedHashMap<String, Object>) map.get("exn");
        LinkedHashMap<String, Object> e = (LinkedHashMap<String, Object>) exn.get("e");
        LinkedHashMap<String, Object> acdc = (LinkedHashMap<String, Object>) e.get("acdc");

        String issuerAid = (String) exn.get("i");
        String credentialId = (String) acdc.get("d");

        System.out.println("Credential ID: " + credentialId);
        System.out.println("Issuer AID: " + issuerAid);

        IpexAdmitArgs admitArgs = buildAdmitArgs(aid.name(), notifications.get(0).a.d, issuerAid);
        executeAdmitProcess(client, aid, issuerAid, admitArgs, notifications.get(0).i);

        System.out.println("Credential admitted successfully!");

        Optional<String> credential = waitForCredential(client, credentialId);
        if (credential.isEmpty()) {
            throw new IllegalStateException("Credential not found with ID: " + credentialId);
        }

        List<Map<String, Object>> cesrData = CESRStreamUtil.parseCESRData(credential.get());
        String stripped = strip(cesrData);

        System.out.println("Credential chain prepared for Cardano transaction.");

        buildTransaction(aid.prefix(), stripped.getBytes(), VLEI_CARDANO_METADATA_SIGNER_SCHEMA_SAID, LEI);

        System.out.println();
        System.out.println("=== Credential Received and Stored on Cardano ===");
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
        l.add("1447");
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
        int numChunks = (data.length + chunkSize - 1) / chunkSize;
        byte[][] chunks = new byte[numChunks][];

        for (int i = 0; i < numChunks; i++) {
            int start = i * chunkSize;
            int end = Math.min(start + chunkSize, data.length);
            chunks[i] = Arrays.copyOfRange(data, start, end);
        }

        return chunks;
    }

    private static RegistryResult createRegistry(SignifyClient client, KeriUtils.Aid aid, String registryName) {
        CreateRegistryArgs registryArgs = CreateRegistryArgs.builder().build();
        registryArgs.setRegistryName(registryName);
        registryArgs.setName(aid.name());
        RegistryResult registryResult;
        try {
            registryResult = client.registries().create(registryArgs);
            Operation<Object> wait = client.operations().wait(Operation.fromObject(registryResult.op()));
            return registryResult;
        } catch (Exception e) {
            // Probably exists
        }
        return null;
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

    public static void getOrCreateContact(SignifyClient client, String name, String oobi) throws IOException, InterruptedException, LibsodiumException {
        List<Contacting.Contact> list = Arrays.asList(client.contacts().list(null, "alias", "^" + name + "$"));
        if (!list.isEmpty()) {
            Contacting.Contact contact = list.getFirst();
            if (contact.getOobi().equals(oobi)) {
                return;
            }
        }
        Object op = client.oobis().resolve(oobi, name);
        Operation<Object> waitOp = client.operations().wait(Operation.fromObject(op));
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
                            boolean isUnread = !Boolean.TRUE.equals(note.r);
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

            Thread.sleep(waitTimeMs);
        }
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
        Exchanging.ExchangeMessageResult admitResult = client.ipex().admit(admitArgs);
        Object operation = client.ipex().submitAdmit(receiver.name(),
                admitResult.exn(), admitResult.sigs(), admitResult.atc(),
                Collections.singletonList(issuerAid));

        Operation<Object> waitOp =
                client.operations().wait(Operation.fromObject(operation));
        if (!waitOp.isDone() || waitOp.getError() != null) {
            throw new IllegalStateException("IPEX admit operation failed: "
                    + (waitOp.getError() != null ? waitOp.getError() : "unknown"));
        }

        client.notifications().mark(notificationId);
        client.operations().delete(waitOp.getName());
    }
}
