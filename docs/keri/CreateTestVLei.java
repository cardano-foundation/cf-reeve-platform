/// usr/bin/env jbang "$0" "$@" ; exit $?
///
// @formatter:off
//JAVA 24+
//COMPILE_OPTIONS --enable-preview -source 24
//RUNTIME_OPTIONS --enable-preview

//DEPS org.cardanofoundation:signify:0.1.1
// @formatter:on

import java.io.IOException;
import java.security.DigestException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cardanofoundation.signify.app.Contacting;
import org.cardanofoundation.signify.app.Exchanging;
import org.cardanofoundation.signify.app.Notifying;
import org.cardanofoundation.signify.app.aiding.CreateIdentifierArgs;
import org.cardanofoundation.signify.app.aiding.EventResult;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Coring;
import org.cardanofoundation.signify.app.coring.Operation;
import org.cardanofoundation.signify.app.credentialing.credentials.CredentialData;
import org.cardanofoundation.signify.app.credentialing.credentials.CredentialFilter;
import org.cardanofoundation.signify.app.credentialing.credentials.IssueCredentialResult;
import org.cardanofoundation.signify.app.credentialing.ipex.IpexAdmitArgs;
import org.cardanofoundation.signify.app.credentialing.ipex.IpexGrantArgs;
import org.cardanofoundation.signify.app.credentialing.registries.CreateRegistryArgs;
import org.cardanofoundation.signify.app.credentialing.registries.RegistryResult;
import org.cardanofoundation.signify.cesr.Salter;
import org.cardanofoundation.signify.cesr.Serder;
import org.cardanofoundation.signify.cesr.exceptions.LibsodiumException;
import org.cardanofoundation.signify.cesr.util.Utils;
import org.cardanofoundation.signify.core.States;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;



public class CreateTestVLei {

    // Type definition for Aid
    private record Aid(String name, String prefix, String oobi) {
    }
    private record ClientAidPair(SignifyClient client, Aid aid) {
    }

    // Notification class similar to TestUtils
    public static class Notification {
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

    private static final String vLEIServer =
            "https://cred-issuance.demo.idw-sandboxes.cf-deployments.org/oobi";
    private static final String keriUrl = "http://localhost:3901";
    private static final String keriBootUrl = "http://localhost:3903";
    private static final String reeveIdentifierBran = "0ADF2TpptgqcDE5IQUF1H";
    private static SignifyClient issuerClient, holderClient, legalEntityClient, reeveClient;
    private static Aid issuerAid, holderAid, legalEntityAid, reeveAid;
    private static String QVI_SCHEMA_SAID = "EBfdlu8R27Fbx-ehrqwImnK-8Cm79sqbAQ4MmvEAYqao";
    private static String LE_SCHEMA_SAID = "ENPXp1vQzRF6JwIuS-mp2U8Uf1MoADoP_GqQ62VsDZWY";
    private static String REEVE_SCHEMA_SAID = "EG9587oc7lSUJGS7mtTkpmRUnJ8F5Ji79-e_pY4jt3Ik";
    // I need to add another SCHEMA_SAID for reeve
    private static String QVI_SCHEMA_URL = vLEIServer + "/" + QVI_SCHEMA_SAID;
    private static String LE_SCHEMA_URL = vLEIServer + "/" + LE_SCHEMA_SAID;
    private static String REEVE_SCHEMA_URL = vLEIServer + "/" + REEVE_SCHEMA_SAID;
    private static final String provenantLEI = "5493001KJTIIGC8Y1R17";
    private static final String CFLEI = "123456789ABCDEF12345"; // dummy LEI for CF
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        System.out.println("Create Test Clients");
        // Creating clients for issuer, holder, legal entity and reeve
        System.out.println("Creating clients and AIDs...");
        ClientAidPair issuerPair = initClientAndAid("issuer", "issuerRegistry", "");
        issuerClient = issuerPair.client();
        issuerAid = issuerPair.aid();

        ClientAidPair holderPair = initClientAndAid("holder", "holderRegistry", "");
        holderClient = holderPair.client();
        holderAid = holderPair.aid();

        ClientAidPair legalEntityPair = initClientAndAid("legalEntity", "legalEntityRegistry", "");
        legalEntityClient = legalEntityPair.client();
        legalEntityAid = legalEntityPair.aid();

        ClientAidPair reevePair = initClientAndAid("reeve", "reeveRegistry", reeveIdentifierBran);
        reeveClient = reevePair.client();
        reeveAid = reevePair.aid();

        resolveOobis(List.of(issuerClient, holderClient, legalEntityClient, reeveClient),
                List.of(QVI_SCHEMA_URL, LE_SCHEMA_URL));
        resolveAidOobis(issuerClient, List.of(holderAid, legalEntityAid, reeveAid));
        resolveAidOobis(holderClient, List.of(issuerAid, legalEntityAid, reeveAid));
        resolveAidOobis(legalEntityClient, List.of(issuerAid, holderAid, reeveAid));
        resolveAidOobis(reeveClient, List.of(issuerAid, holderAid, legalEntityAid));
        System.out.println("Creating holder credential");

        String qviCredentialId = createCredential();

        System.out.println("Holder Credential ID: " + qviCredentialId + " Sending IPEX grant...");
        sentIpexGrant(qviCredentialId, issuerAid, holderAid, issuerClient);

        System.out.println("Holder IPEX admit");
        
        List<CreateTestVLei.Notification> filteredNotifications = waitForNotifications();
        if (filteredNotifications == null) {
            throw new IllegalStateException("No notifications received after retries");
        }

        // Map<String, Object> grantNotification;
        // if (filteredNotifications.size() > 0) {
        //     Notification notification = filteredNotifications.getFirst();
        //     // Convert back to Map format for compatibility with existing code
        //     grantNotification = new HashMap<>();
        //     grantNotification.put("i", notification.i);
        //     grantNotification.put("dt", notification.dt);
        //     grantNotification.put("r", notification.r);
        //     Map<String, Object> aMap = new HashMap<>();
        //     aMap.put("r", notification.a.r);
        //     aMap.put("d", notification.a.d);
        //     aMap.put("m", notification.a.m);
        //     grantNotification.put("a", aMap);
        // }
        // String dt = new Date().toInstant().toString().replace("Z", "000+00:00");
        // IpexAdmitArgs iargs = IpexAdmitArgs.builder().build();
        // iargs.setSenderName(holderAid.name);
        // iargs.setMessage("");
        // @SuppressWarnings("unchecked")
        // Map<String, Object> aMap = (Map<String, Object>) grantNotification.get("a");
        // iargs.setGrantSaid((String) aMap.get("d"));
        // iargs.setRecipient(issuerAid.prefix);
        // iargs.setDatetime(dt);

        // Exchanging.ExchangeMessageResult resultApexAdmint = holderClient.ipex().admit(iargs);
        // Object op = holderClient.ipex().submitAdmit(holderAid.name, resultApexAdmint.exn(),
        //         resultApexAdmint.sigs(), resultApexAdmint.atc(),
        //         Collections.singletonList(issuerAid.prefix));
        // holderClient.operations().wait(Operation.fromObject(op));
        // System.out
        //         .println("Holder Credential: " + qviCredentialId + " issued to " + holderAid.name);
    }


    private static List<CreateTestVLei.Notification> waitForNotifications() throws IOException, InterruptedException {
        int retryCount = 10;
        int waitTimeMs = 3000;
        String route = "/exn/ipex/grant";
        Map<String, Object> grantNotification = null;
        for (int i = 0; i < retryCount; i++) {
            System.out.println(
                    "Checking for notifications, attempt " + (i + 1) + " of " + retryCount);
            Notifying.Notifications.NotificationListResponse response =
                    holderClient.notifications().list();
            String notesResponse = response.notes();
            System.out.println(notesResponse);
            List<Notification> holderNotifications =
                    Utils.fromJson(notesResponse, new TypeReference<>() {});
            System.out.println(holderNotifications.size() + " notifications found");
            List<Notification> filteredNotifications = holderNotifications.stream().filter(note -> {
                // Check if notification has not been read yet (r field should be false)
                boolean isUnread = !Boolean.TRUE.equals(note.r);
                // Check if route matches
                boolean routeMatches = note.a != null && route.equals(note.a.r);
                return isUnread && routeMatches;
            }).toList();
            System.out.println(filteredNotifications.size() + " ipex grant notifications found");
            if(filteredNotifications.size() > 0) {
                return filteredNotifications;
            }
            System.out.println("No notifications yet, waiting...");
            Thread.sleep(waitTimeMs);
        }
        return null;
    }


    private static String createCredential()
            throws IOException, InterruptedException, DigestException {
        List<Map<String, Object>> registriesList =
                (List<Map<String, Object>>) issuerClient.registries().list(issuerAid.name);

        CredentialData.CredentialSubject a = CredentialData.CredentialSubject.builder().build();
        a.setI(holderAid.prefix);
        a.setAdditionalProperties(Map.of("LEI", provenantLEI));
        CredentialData cData = CredentialData.builder().build();
        cData.setA(a);
        cData.setS(QVI_SCHEMA_SAID);
        cData.setRi(registriesList.getFirst().get("regk").toString());

        IssueCredentialResult holderCredentialResult =
                issuerClient.credentials().issue(issuerAid.name(), cData);
        issuerClient.operations().wait(holderCredentialResult.getOp());
        String qviCredentialId = holderCredentialResult.getAcdc().getKed().get("d").toString();
        return qviCredentialId;
    }


    private static void sentIpexGrant(String qviCredentialId, Aid issuerAid, Aid holderAid,
            SignifyClient issuerClient) throws IOException, InterruptedException, DigestException {
        String dt = new Date().toInstant().toString().replace("Z", "000+00:00");

        Object issuerCredential = issuerClient.credentials().get(qviCredentialId);
        LinkedHashMap<String, Object> issuerCredentialList =
                (LinkedHashMap<String, Object>) issuerCredential;
        @SuppressWarnings("unchecked")
        Map<String, Object> getSAD = (Map<String, Object>) issuerCredentialList.get("sad");
        Map<String, Object> getANC = (Map<String, Object>) issuerCredentialList.get("anc");
        Map<String, Object> getISS = (Map<String, Object>) issuerCredentialList.get("iss");
        IpexGrantArgs gArgs = IpexGrantArgs.builder().build();
        gArgs.setSenderName(issuerAid.name);
        gArgs.setAcdc(new Serder(getSAD));
        gArgs.setAnc(new Serder(getANC));
        gArgs.setIss(new Serder(getISS));
        gArgs.setAncAttachment(null);
        gArgs.setRecipient(holderAid.prefix);
        gArgs.setDatetime(dt);
        Exchanging.ExchangeMessageResult result = issuerClient.ipex().grant(gArgs);
        List<String> holderAidPrefix = Collections.singletonList(holderAid.prefix);
        Object op = issuerClient.ipex().submitGrant(issuerAid.name, result.exn(), result.sigs(),
                result.atc(), holderAidPrefix);
        Operation<Object> wait2 = issuerClient.operations().wait(Operation.fromObject(op));
        if (!wait2.isDone() || wait2.getError() != null) {
            throw new IllegalStateException("Operation not done yet or error: "
                    + (wait2.getError() != null ? wait2.getError() : "unknown"));
        }
        issuerClient.operations().delete(wait2.getName());
    }


    public static ClientAidPair initClientAndAid(String name, String registryName, String bran)
            throws Exception {
        SignifyClient client = getOrCreateClient(bran);
        Aid aid = createAid(client, name);
        createRegistry(client, aid, registryName);
        return new ClientAidPair(client, aid);
    }

    public static SignifyClient getOrCreateClient(String bran) throws Exception {

        if (bran == null || bran.isEmpty()) {
            bran = Coring.randomPasscode();
        }

        SignifyClient client = new SignifyClient(keriUrl, bran, Salter.Tier.low, keriBootUrl, null);
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
        try {
            States.HabState identifier = client.identifiers().get(name);
            id = identifier.getPrefix();
        } catch (Exception e) {
            EventResult result =
                    client.identifiers().create(name, CreateIdentifierArgs.builder().build());
            Object op = client.operations().wait(Operation.fromObject(result.op()));
            if (op instanceof String) {
                try {
                    HashMap<String, Object> map =
                            objectMapper.readValue((String) op, HashMap.class);
                    HashMap<String, Object> idMap = (HashMap<String, Object>) map.get("response");
                    id = idMap.get("i");
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
            if (client.getAgent() != null && client.getAgent().getPre() != null) {
                eid = client.getAgent().getPre();
            } else {
                throw new IllegalStateException("Agent or pre is null");
            }
            EventResult results = client.identifiers().addEndRole(name, "agent", eid, null);
            client.operations().wait(Operation.fromObject(results.op()));
        }

        Object oobi = client.oobis().get(name, "agent");
        String getOobi = ((LinkedHashMap) oobi).get("oobis").toString().replaceAll("[\\[\\]]", "");
        String[] result = new String[] {id != null ? id.toString() : eid, getOobi};
        return new Aid(name, result[0], result[1]);
    }

    public static void resolveOobis(List<SignifyClient> clients, List<String> oobis)
            throws Exception {
        for (int i = 0; i < clients.size(); i++) {
            SignifyClient client = clients.get(i);
            for (String oobi : oobis) {
                // System.out.println("Resolving OOBI for " + oobi);
                Object result = client.oobis().resolve(oobi, null);
                client.operations().wait(Operation.fromObject(result));
            }
        }
    }

    public static void resolveAidOobis(SignifyClient client, List<Aid> aids) throws Exception {
        for (Aid aid : aids) {
            List<Contacting.Contact> list = Arrays.asList(client.contacts().list(null, "alias", "^" + aid.name + "$"));
            if (!list.isEmpty()) {
                Contacting.Contact contact = list.getFirst();
                if (contact.getOobi().equals(aid.oobi)) {
                    continue;
                }
            }
            Object op = client.oobis().resolve(aid.oobi, aid.name);
            Operation<Object> opBody = client.operations().wait(Operation.fromObject(op));
            LinkedHashMap<String, Object> response = (LinkedHashMap<String,Object>)opBody.getResponse();

            if (response.get("i") == null) {
                aids.add(aid); // repeat it
                System.out.println("Failed to resolve OOBI for " + aid.name + ", retrying...");
            }
        }
    }

    public static void createRegistry(SignifyClient client, Aid aid, String registryName) {
        CreateRegistryArgs registryArgs = CreateRegistryArgs.builder().build();
        registryArgs.setRegistryName(registryName);
        registryArgs.setName(aid.name);
        RegistryResult registryResult;
        try {
            registryResult = client.registries().create(registryArgs);
            client.operations().wait(Operation.fromObject(registryResult.op()));
        } catch (Exception e) {
            System.out.println(
                    "Registry " + registryName + " probably already exists for " + aid.name);
        }
    }
}
