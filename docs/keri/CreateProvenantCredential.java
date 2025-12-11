/// usr/bin/env jbang "$0" "$@" ; exit $?
///
// @formatter:off
//JAVA 24+
//COMPILE_OPTIONS -source 24
//RUNTIME_OPTIONS

//REPOS snapshots=https://central.sonatype.com/repository/maven-snapshots/
//REPOS central=https://repo.maven.apache.org/maven2
//DEPS org.cardanofoundation:signify:0.1.2-ebfb904-SNAPSHOT
//DEPS com.bloxbean.cardano:cardano-client-lib:0.7.0-beta2
//DEPS com.bloxbean.cardano:cardano-client-backend-blockfrost:0.7.0-beta2
// @formatter:on

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
import org.cardanofoundation.signify.app.aiding.CreateIdentifierArgs;
import org.cardanofoundation.signify.app.aiding.EventResult;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Coring;
import org.cardanofoundation.signify.app.coring.Operation;
import org.cardanofoundation.signify.app.credentialing.credentials.CredentialData;
import org.cardanofoundation.signify.app.credentialing.ipex.IpexAdmitArgs;
import org.cardanofoundation.signify.app.credentialing.registries.CreateRegistryArgs;
import org.cardanofoundation.signify.app.credentialing.registries.RegistryResult;
import org.cardanofoundation.signify.cesr.Salter;
import org.cardanofoundation.signify.cesr.exceptions.LibsodiumException;
import org.cardanofoundation.signify.cesr.util.CoreUtil;
import org.cardanofoundation.signify.cesr.util.Utils;
import org.cardanofoundation.signify.core.States;
import org.cardanofoundation.signify.core.States.HabState;

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
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public class CreateProvenantCredential {

    // ------- Constants ------- //
    private static final String CLIENT_NAME = "GTReeveClient";
    private static final String REGISTRY_NAME = "GTRegistry";
    private static final String IDENTIFIER_BRAN = "0ADF2TpptgqcDE5IQUF1H"; // TODO Need to randomized or passed in
    
    public static final String QVI_SCHEMA_SAID = "EBfdlu8R27Fbx-ehrqwImnK-8Cm79sqbAQ4MmvEAYqao";
    public static final String LE_SCHEMA_SAID = "ENPXp1vQzRF6JwIuS-mp2U8Uf1MoADoP_GqQ62VsDZWY";
    private static final String REEVE_SCHEMA_SAID = "ED_GbUPpS8ZJEY-u8OB3QVe9C_CAFBdSimS5KxclkgWT";

    public static final String vLEIServer = "https://cred-issuance.demo.idw-sandboxes.cf-deployments.org/oobi";
    public static final String KERI_URL = "https://keria.staging.cardano-foundation.app.reeve.technology";
    public static final String KERI_BOOT_URL = "https://keria-boot.staging.cardano-foundation.app.reeve.technology";
    // Schemas
    public static final String REEVE_SCHEMA_URL = vLEIServer + "/" + REEVE_SCHEMA_SAID;
    public static final String LE_SCHEMA_URL = vLEIServer + "/" + LE_SCHEMA_SAID;
    public static final String QVI_SCHEMA_URL = vLEIServer + "/" + QVI_SCHEMA_SAID;

    private static final List<String> WITNESS_IDS = Arrays.asList();

    // TODO Replace with actual Issuer AID and Parent Credential ID
    private static final String ISSUER_AID = "DUMMY_AID";
    private static final String PARENT_CREDENTIAL_ID = "DUMMY_CREDENTIAL_ID";
    private static final String LEI = "5493001KJTIIGC8Y1R12"; // Dummy LEI for testing

    // Wallet specific constants
    private static final String mnemonic = "test test test test test test test test test test test test test test test test test test test test test test test sauce";
    private static final Network network = Networks.testnet();
    private static final BackendService backendService = new BFBackendService("http://localhost:8081/api/v1/", "Dummy Key");
    private static final QuickTxBuilder QuickTxBuilder = new QuickTxBuilder(backendService);



    private static final List<Aid> oobis = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        System.out.println("=== vLEI Credential Chain Setup Starting ===");


        // --- SETUP REEVE CLIENT AND REGISTRY --- //
        SignifyClient client = getOrCreateClient(IDENTIFIER_BRAN);
        Aid aid = createAid(client, CLIENT_NAME);
        RegistryResult registry = createRegistry(client, aid, REGISTRY_NAME);
        
        // TODO Also Reeve schema?
        List<String> schemaUrls = List.of(QVI_SCHEMA_URL, LE_SCHEMA_URL, REEVE_SCHEMA_URL);
        resolveSchemas(client, schemaUrls);
        resolveAidOobis(client, oobis);

        System.out.println("Client AID: " + aid.prefix() + " OOBI: " + aid.oobi());

        // ------- IPEX ADMIT ------- //

        List<Notification> notifications = waitForNotifications("/exn/ipex/grant", client, aid);
        if (notifications == null || notifications.isEmpty()) {
            throw new IllegalStateException(
                    "No grant notifications received");
        }
        IpexAdmitArgs admitArgs = buildAdmitArgs(aid.name(), notifications.get(0).a.d,
                    ISSUER_AID);
        executeAdmitProcess(client, aid, ISSUER_AID, admitArgs, notifications.get(0).i);

        // ------- Building the transaction ------- //

        Optional<Object> credential = client.credentials().get(aid.prefix, true);
        String cesrQb64 = (String) credential.orElseThrow();
        Optional<HabState> optional = client.identifiers().get(CLIENT_NAME);
        String prefix = optional.orElseThrow().getPrefix();
        buildTransaction(prefix, cesrQb64.getBytes(), QVI_SCHEMA_SAID, LEI);

        System.out.println("=== vLEI Credential Chain Setup Completed ===");
    }

    static void buildTransaction(String aid, byte[] credentialChain, String saidOfLeafCredentialSchema, String lei) {
        Account account = Account.createFromMnemonic(network, mnemonic);

        MetadataMap metadataMap = MetadataBuilder.createMap();
        metadataMap.put("t", "AUTH_BEGIN");
        metadataMap.put("s", saidOfLeafCredentialSchema);
        metadataMap.put("s", credentialChain);
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
        System.out.println("Transaction submitted. Tx Hash: " + txResult.getTxHash());
    }

    // ------- Helper Methods ------- //

    private static record Aid(String name, String prefix, String oobi) {}

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

    public static Aid createAid(SignifyClient client, String name) throws Exception {
        Object id = null;
        String eid = "";
        CreateIdentifierArgs kArgs = CreateIdentifierArgs.builder().build();
        kArgs.setToad(WITNESS_IDS.size());
        kArgs.setWits(WITNESS_IDS);
        Object op, ops;
        Optional<States.HabState> optionalIdentifier = client.identifiers().get(name);
        if (optionalIdentifier.isPresent()) {
            id = optionalIdentifier.get().getPrefix();
        } else {
            EventResult result = client.identifiers().create(name, kArgs);
            op = result.op();
            op = client.operations().wait(Operation.fromObject(op));
            LinkedHashMap<String, Object> resp = (LinkedHashMap<String, Object>) (Operation.fromObject(op).getResponse());
        
            id = resp.get("i");
            if (client.getAgent() != null && client.getAgent().getPre() != null) {
                eid = client.getAgent().getPre();
            } else {
                throw new IllegalStateException("Agent or pre is null");
            }
            if (!hasEndRole(client, name, "agent", eid)) {
                EventResult results = client.identifiers().addEndRole(name, "agent", eid, null);
                ops = results.op();
                ops = client.operations().wait(Operation.fromObject(ops));
            }
        }

        Object oobi = client.oobis().get(name, "agent").get();
        String getOobi = ((LinkedHashMap) oobi).get("oobis").toString().replaceAll("[\\[\\]]", "");
        String[] result = new String[] {id != null ? id.toString() : null, getOobi};
        return new Aid(name, result[0], result[1]);
    }

    public static Boolean hasEndRole(SignifyClient client, String alias, String role, String eid)
            throws Exception {
        List<Map<String, Object>> list = getEndRoles(client, alias, role);
        for (Map<String, Object> endRoleMap : list) {
            String endRole = (String) endRoleMap.get("role");
            String endRoleEid = (String) endRoleMap.get("eid");

            if (endRole != null && endRoleEid != null && endRole.equals(role)
                    && endRoleEid.equals(eid)) {
                return true;
            }
        }
        return false;
    }

    public static List<Map<String, Object>> getEndRoles(SignifyClient client, String alias, String role) throws Exception {
        String path = (role != null)
                ? "/identifiers/" + alias + "/endroles/" + role
                : "/identifiers/" + alias + "/endroles";

        HttpResponse<String> response = client.fetch(path, "GET", alias, null);
        String responseBody = response.body();

        ObjectMapper objectMapper = new ObjectMapper();
        List<Map<String, Object>> result = objectMapper.readValue(responseBody, new TypeReference<>() {
        });
        return result;
    }

    private static RegistryResult createRegistry(SignifyClient client, Aid aid, String registryName) {
        CreateRegistryArgs registryArgs = CreateRegistryArgs.builder().build();
        registryArgs.setRegistryName(registryName);
        registryArgs.setName(aid.name());
        RegistryResult registryResult;
        try {
            registryResult = client.registries().create(registryArgs);
            
            Operation<Object> wait = client.operations().wait(Operation.fromObject(registryResult.op()));
            return registryResult;
        } catch (Exception e) {
            System.out.println(
                    "Registry " + registryName + " probably already exists for " + aid.name());
        }
        return null;
    }

    private static void resolveSchemas(SignifyClient client, List<String> schemaUrls) {
        for (String schemaUrl : schemaUrls) {
            try {
                Object result = client.oobis().resolve(schemaUrl, null);
                client.operations().wait(Operation.fromObject(result));
            } catch (LibsodiumException | IOException | InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            
        }
    }

    public static void resolveAidOobis(SignifyClient client, List<Aid> aids) throws Exception {
        for (Aid aid : aids) {
            // Check if contact already exists
            List<Contacting.Contact> list =
                    Arrays.asList(client.contacts().list(null, "alias", "^" + aid.name() + "$"));
            if (!list.isEmpty()) {
                Contacting.Contact contact = list.getFirst();
                if (contact.getOobi().equals(aid.oobi())) {
                    continue;
                }
            }

            try {
                Object op = client.oobis().resolve(aid.oobi(), aid.name());
                Operation<Object> opBody = client.operations().wait(Operation.fromObject(op));
                @SuppressWarnings("unchecked")
                LinkedHashMap<String, Object> response =
                        (LinkedHashMap<String, Object>) opBody.getResponse();

                if (response.get("i") == null) {
                    aids.add(aid); // repeat it
                }
            } catch (Exception e) {
                System.out.println("Error resolving OOBI for " + aid.name() + ": " + e.getMessage());
                throw e;
            }
        }
    }

    private static List<Notification> waitForNotifications(String route,
            SignifyClient client, Aid aid) throws IOException, InterruptedException {
        int retryCount = 10;
        int waitTimeMs = 3000;

        System.out.println("Waiting for notifications for route: " + route);
        System.out.println("Receiver client prefix: " + aid.prefix());

        for (int i = 0; i < retryCount; i++) {
            System.out.println(
                    "Checking for notifications, attempt " + (i + 1) + " of " + retryCount);

            try {
                Notifying.Notifications.NotificationListResponse response =
                        client.notifications().list();
                String notesResponse = response.notes();
                System.out.println("Raw notification response: " + notesResponse);

                List<Notification> receiverNotifications =
                        Utils.fromJson(notesResponse, new TypeReference<>() {});
                System.out.println(receiverNotifications.size() + " total notifications found");

                List<Notification> filteredNotifications =
                        receiverNotifications.stream().filter(note -> {
                            // Check if notification has not been read yet (r field should be false)
                            boolean isUnread = !Boolean.TRUE.equals(note.r);
                            // Check if route matches
                            boolean routeMatches = note.a != null && route.equals(note.a.r);

                            System.out.println("Filtering notification - isUnread: " + isUnread
                                    + ", routeMatches: " + routeMatches);
                            return isUnread && routeMatches;
                        }).toList();

                System.out.println(filteredNotifications.size()
                        + " matching notifications found for route: " + route);
                if (filteredNotifications.size() > 0) {
                    return filteredNotifications;
                }
            } catch (Exception e) {
                System.out.println("Error retrieving notifications: " + e.getMessage());
                e.printStackTrace();
            }

            System.out.println("No matching notifications yet, waiting " + waitTimeMs + "ms...");
            Thread.sleep(waitTimeMs);
        }
        return null;
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

    private static void executeAdmitProcess(SignifyClient client, Aid receiver, String issuerAid,
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

    private static CredentialData.CredentialSubject buildCredentialSubject(
            SignifyClient issuerClient, Aid recipientAid, String lei) {

        Map<String, Object> additionalProperties = new LinkedHashMap<>();
        additionalProperties.put("LEI", lei);

        CredentialData.CredentialSubject subject =
                CredentialData.CredentialSubject.builder().build();
        subject.setI(resolveRecipientContact(issuerClient, recipientAid));
        subject.setAdditionalProperties(additionalProperties);
        return subject;
    }

    private static String resolveRecipientContact(SignifyClient issuerClient, Aid recipientAid) {
        try {
            String contactId = getContactId(issuerClient, recipientAid.name());
            if (contactId != null) {
                System.out.println("Using resolved contact ID: " + contactId);
                return contactId;
            }
        } catch (Exception e) {
            System.out.println("ERROR resolving contact ID: " + e.getMessage());
        }

        System.out.println("WARNING: Using original prefix as fallback: " + recipientAid.prefix());
        return recipientAid.prefix();
    }

    public static String getContactId(SignifyClient client, String alias) throws Exception {
        List<Contacting.Contact> contacts =
                Arrays.asList(client.contacts().list(null, "alias", "^" + alias + "$"));
        if (!contacts.isEmpty()) {
            return contacts.getFirst().getId();
        }
        return null;
    }
}