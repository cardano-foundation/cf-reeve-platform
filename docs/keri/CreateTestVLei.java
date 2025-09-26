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
    private static ClientAidPair issuer, holder, legalEntity, reeve;
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
    private static final List<String> witnessIds =
            List.of("BBilc4-L3tFUnfM_wJr4S4OJanAv_VmF_dJNN6vkf2Ha",
                    "BLskRTInXnMxWaGqcpSyMgo0nYbalW99cGZESrz3zapM",
                    "BIKKuvBwpmDVA4Ds-EpL5bt9OqPzWPja2LigFYZN2YfX");

    public static void main(String[] args) throws Exception {
        System.out.println("Create Test Clients");
        // Creating clients for issuer, holder, legal entity and reeve
        System.out.println("Creating clients and AIDs...");
        issuer = initClientAndAid("issuer", "issuerRegistry", "");
        holder = initClientAndAid("holder", "holderRegistry", "");
        legalEntity = initClientAndAid("legalEntity", "legalEntityRegistry", "");
        reeve = initClientAndAid("reeve", "reeveRegistry", reeveIdentifierBran);

        System.out.println("Resolving schema OOBIs...");
        resolveOobis(
                List.of(issuer.client(), holder.client(), legalEntity.client(), reeve.client()),
                List.of(QVI_SCHEMA_URL, LE_SCHEMA_URL, REEVE_SCHEMA_URL));
        
        System.out.println("Resolving AID OOBIs for inter-client communication...");
        resolveAidOobis(issuer.client, List.of(holder.aid(), legalEntity.aid(), reeve.aid()));
        resolveAidOobis(holder.client, List.of(issuer.aid(), legalEntity.aid(), reeve.aid()));
        resolveAidOobis(legalEntity.client, List.of(issuer.aid(), holder.aid(), reeve.aid()));
        resolveAidOobis(reeve.client, List.of(issuer.aid(), holder.aid(), legalEntity.aid()));
        
        System.out.println("All OOBIs resolved successfully!");
        
        // Verify client connections and contacts
        System.out.println("Verifying client connections...");
        verifyClientConnections();
        
        System.out.println("Creating holder credential");

        String qviCredentialId = createCredential();

        System.out.println("Holder Credential ID: " + qviCredentialId + " Sending IPEX grant...");
        sentIpexGrant(qviCredentialId, issuer.aid(), holder.aid(), issuer.client());

        System.out.println("Waiting a moment for the grant to be processed...");
        Thread.sleep(2000); // Give some time for the grant to be processed

        System.out.println("Holder IPEX admit");
        List<CreateTestVLei.Notification> filteredNotifications =
                waitForNotifications("/exn/ipex/grant");
        if (filteredNotifications == null) {
            throw new IllegalStateException("No notifications received after retries");
        }

        // Process the grant notification and send admit
        if (filteredNotifications.size() > 0) {
            Notification grantNotification = filteredNotifications.getFirst();
            System.out.println("Processing grant notification: " + grantNotification.a.d);
            
            // Send IPEX admit from holder
            String dt = new Date().toInstant().toString().replace("Z", "000+00:00");
            IpexAdmitArgs iargs = IpexAdmitArgs.builder().build();
            iargs.setSenderName(holder.aid().name);
            iargs.setMessage("");
            iargs.setGrantSaid(grantNotification.a.d);
            iargs.setRecipient(issuer.aid().prefix);
            iargs.setDatetime(dt);

            Exchanging.ExchangeMessageResult resultAdmit = holder.client().ipex().admit(iargs);
            Object op = holder.client().ipex().submitAdmit(holder.aid().name, resultAdmit.exn(),
                    resultAdmit.sigs(), resultAdmit.atc(),
                    Collections.singletonList(issuer.aid().prefix));
            Operation<Object> waitOp = holder.client().operations().wait(Operation.fromObject(op));
            
            if (!waitOp.isDone() || waitOp.getError() != null) {
                throw new IllegalStateException("IPEX admit operation failed: " 
                        + (waitOp.getError() != null ? waitOp.getError() : "unknown"));
            }
            holder.client().operations().delete(waitOp.getName());
            
            System.out.println("Holder Credential: " + qviCredentialId + " admitted by " + holder.aid().name);
        }
    }


    private static List<CreateTestVLei.Notification> waitForNotifications(String route)
            throws IOException, InterruptedException {
        int retryCount = 10;
        int waitTimeMs = 3000;
        
        System.out.println("Waiting for notifications for route: " + route);
        System.out.println("Holder client prefix: " + holder.aid().prefix);
        
        for (int i = 0; i < retryCount; i++) {
            System.out.println(
                    "Checking for notifications, attempt " + (i + 1) + " of " + retryCount);
            
            try {
                Notifying.Notifications.NotificationListResponse response =
                        holder.client().notifications().list();
                String notesResponse = response.notes();
                System.out.println("Raw notification response: " + notesResponse);
                
                List<Notification> holderNotifications =
                        Utils.fromJson(notesResponse, new TypeReference<>() {});
                System.out.println(holderNotifications.size() + " total notifications found");
                
                // Print all notifications for debugging
                for (int j = 0; j < holderNotifications.size(); j++) {
                    Notification note = holderNotifications.get(j);
                    System.out.println("Notification " + j + ":");
                    System.out.println("  - i: " + note.i);
                    System.out.println("  - dt: " + note.dt);
                    System.out.println("  - r (read): " + note.r);
                    if (note.a != null) {
                        System.out.println("  - a.r (route): " + note.a.r);
                        System.out.println("  - a.d (said): " + note.a.d);
                        System.out.println("  - a.m (message): " + note.a.m);
                    } else {
                        System.out.println("  - a: null");
                    }
                }
                
                List<Notification> filteredNotifications = holderNotifications.stream().filter(note -> {
                    // Check if notification has not been read yet (r field should be false)
                    boolean isUnread = !Boolean.TRUE.equals(note.r);
                    // Check if route matches
                    boolean routeMatches = note.a != null && route.equals(note.a.r);
                    
                    System.out.println("Filtering notification - isUnread: " + isUnread + ", routeMatches: " + routeMatches);
                    return isUnread && routeMatches;
                }).toList();
                
                System.out.println(filteredNotifications.size() + " matching notifications found for route: " + route);
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


    private static String createCredential()
            throws IOException, InterruptedException, DigestException {
        List<Map<String, Object>> registriesList =
                (List<Map<String, Object>>) issuer.client().registries().list(issuer.aid().name);

        CredentialData.CredentialSubject a = CredentialData.CredentialSubject.builder().build();
        a.setI(holder.aid().prefix);
        a.setAdditionalProperties(Map.of("LEI", provenantLEI));
        CredentialData cData = CredentialData.builder().build();
        cData.setA(a);
        cData.setS(QVI_SCHEMA_SAID);
        cData.setRi(registriesList.getFirst().get("regk").toString());

        IssueCredentialResult holderCredentialResult =
                issuer.client().credentials().issue(issuer.aid().name(), cData);
        issuer.client().operations().wait(holderCredentialResult.getOp());
        String qviCredentialId = holderCredentialResult.getAcdc().getKed().get("d").toString();
        return qviCredentialId;
    }


    private static void sentIpexGrant(String qviCredentialId, Aid issuerAid, Aid holderAid,
            SignifyClient issuerClient) throws IOException, InterruptedException, DigestException {
        System.out.println("Starting IPEX grant process...");
        System.out.println("Issuer: " + issuerAid.name + " (" + issuerAid.prefix + ")");
        System.out.println("Holder: " + holderAid.name + " (" + holderAid.prefix + ")");
        System.out.println("Credential ID: " + qviCredentialId);
        
        String dt = new Date().toInstant().toString().replace("Z", "000+00:00");

        Object issuerCredential = issuerClient.credentials().get(qviCredentialId);
        LinkedHashMap<String, Object> issuerCredentialList =
                (LinkedHashMap<String, Object>) issuerCredential;
        @SuppressWarnings("unchecked")
        Map<String, Object> getSAD = (Map<String, Object>) issuerCredentialList.get("sad");
        Map<String, Object> getANC = (Map<String, Object>) issuerCredentialList.get("anc");
        Map<String, Object> getISS = (Map<String, Object>) issuerCredentialList.get("iss");
        
        System.out.println("Building IPEX grant arguments...");
        IpexGrantArgs gArgs = IpexGrantArgs.builder().build();
        gArgs.setSenderName(issuerAid.name);
        gArgs.setAcdc(new Serder(getSAD));
        gArgs.setAnc(new Serder(getANC));
        gArgs.setIss(new Serder(getISS));
        gArgs.setAncAttachment(null);
        // Get the resolved holder contact ID from issuer's contacts
        String holderContactId;
        try {
            holderContactId = getContactId(issuerClient, "holder");
            if (holderContactId == null) {
                System.out.println("WARNING: Using original holder prefix since contact not found");
                holderContactId = holderAid.prefix;
            }
        } catch (Exception e) {
            System.out.println("ERROR getting contact ID: " + e.getMessage());
            holderContactId = holderAid.prefix;
        }
        System.out.println("Using holder contact ID: " + holderContactId);
        gArgs.setRecipient(holderContactId);
        gArgs.setDatetime(dt);
        
        System.out.println("Creating IPEX grant message...");
        Exchanging.ExchangeMessageResult result = issuerClient.ipex().grant(gArgs);
        
        System.out.println("Submitting IPEX grant to holder...");
        List<String> holderAidPrefix = Collections.singletonList(holderContactId);
        Object op = issuerClient.ipex().submitGrant(issuerAid.name, result.exn(), result.sigs(),
                result.atc(), holderAidPrefix);
        
        System.out.println("Waiting for IPEX grant operation to complete...");
        Operation<Object> wait2 = issuerClient.operations().wait(Operation.fromObject(op));
        if (!wait2.isDone() || wait2.getError() != null) {
            throw new IllegalStateException("IPEX grant operation failed: "
                    + (wait2.getError() != null ? wait2.getError() : "unknown"));
        }
        issuerClient.operations().delete(wait2.getName());
        System.out.println("IPEX grant sent successfully!");
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
            CreateIdentifierArgs kArgs = CreateIdentifierArgs.builder().build();
            kArgs.setToad(witnessIds.size());
            kArgs.setWits(witnessIds);
            EventResult result = client.identifiers().create(name, kArgs);
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
        for (SignifyClient client : clients) {
            for (String oobi : oobis) {
                // System.out.println("Resolving OOBI for " + oobi);
                Object result = client.oobis().resolve(oobi, null);
                client.operations().wait(Operation.fromObject(result));
            }
        }
    }

    public static void resolveAidOobis(SignifyClient client, List<Aid> aids) throws Exception {
        for (Aid aid : aids) {
            
            // Check if contact already exists
            List<Contacting.Contact> list =
                    Arrays.asList(client.contacts().list(null, "alias", "^" + aid.name + "$"));
            if (!list.isEmpty()) {
                Contacting.Contact contact = list.getFirst();
                if (contact.getOobi().equals(aid.oobi)) {
                    continue;
                }
            }
            
            try {
                Object op = client.oobis().resolve(aid.oobi, aid.name);
                Operation<Object> opBody = client.operations().wait(Operation.fromObject(op));
                LinkedHashMap<String, Object> response =
                        (LinkedHashMap<String, Object>) opBody.getResponse();

                System.out.println("OOBI resolution response: " + response);
                
                if (response.get("i") == null) {
                    aids.add(aid); // repeat it
                }
            } catch (Exception e) {
                System.out.println("Error resolving OOBI for " + aid.name + ": " + e.getMessage());
                throw e;
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
    
    public static void verifyClientConnections() throws Exception {
        List<Contacting.Contact> issuerContacts = Arrays.asList(issuer.client().contacts().list(null, null, null));        
        List<Contacting.Contact> holderContacts = Arrays.asList(holder.client().contacts().list(null, null, null));
        List<Contacting.Contact> legalEntityContacts = Arrays.asList(legalEntity.client().contacts().list(null, null, null));
        List<Contacting.Contact> reeveContacts = Arrays.asList(reeve.client().contacts().list(null, null, null));
        // Verify specific contact exists by name rather than prefix (since prefix might be different after OOBI resolution)
        boolean issuerKnowsHolder = issuerContacts.stream().anyMatch(c -> "holder".equals(c.getAlias()));
        boolean holderKnowsIssuer = holderContacts.stream().anyMatch(c -> "issuer".equals(c.getAlias()));
        boolean legalEntityKnowsHolder = legalEntityContacts.stream().anyMatch(c -> "holder".equals(c.getAlias()));
        boolean holderKnowsLegalEntity = holderContacts.stream().anyMatch(c -> "legalEntity".equals(c.getAlias()));
        boolean reeveKnowsHolder = reeveContacts.stream().anyMatch(c -> "holder".equals(c.getAlias()));
        boolean holderKnowsReeve = holderContacts.stream().anyMatch(c -> "reeve".equals(c.getAlias()));
        if (!issuerKnowsHolder || !holderKnowsIssuer || !legalEntityKnowsHolder || !holderKnowsLegalEntity
                || !reeveKnowsHolder || !holderKnowsReeve) {
            System.out.println("WARNING: Not all contacts are properly established by alias!");
            
            // Try by prefix as fallback
            boolean issuerKnowsHolderByPrefix = issuerContacts.stream().anyMatch(c -> holder.aid().prefix.equals(c.getId()));
            boolean holderKnowsIssuerByPrefix = holderContacts.stream().anyMatch(c -> issuer.aid().prefix.equals(c.getId()));
            System.out.println("Fallback - Issuer knows holder (by prefix): " + issuerKnowsHolderByPrefix);
            System.out.println("Fallback - Holder knows issuer (by prefix): " + holderKnowsIssuerByPrefix);
        } else {
            System.out.println("All contacts verified successfully by alias!");
        }
    }
    
    public static String getContactId(SignifyClient client, String alias) throws Exception {
        List<Contacting.Contact> contacts = Arrays.asList(client.contacts().list(null, "alias", "^" + alias + "$"));
        if (!contacts.isEmpty()) {
            return contacts.getFirst().getId();
        }
        return null;
    }
}
