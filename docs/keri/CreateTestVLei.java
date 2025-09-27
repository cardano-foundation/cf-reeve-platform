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
        System.out.println("=== vLEI Test Setup Starting ===");
        
        // Step 1: Initialize all clients and identifiers
        initializeClients();
        
        // Step 2: Establish communication channels
        establishCommunicationChannels();
        
        // Step 3: Create and issue QVI credential
        String qviCredentialId = createAndIssueQviCredential();
        
        // Step 4: Create and issue Legal Entity credential (chained)
        String leCredentialId = createAndIssueLegalEntityCredential(qviCredentialId);
        
        // Step 5: Display final results
        displayFinalResults(leCredentialId);
        
        System.out.println("=== vLEI Test Setup Complete ===");
    }

    /**
     * Initialize all client identifiers and registries
     */
    private static void initializeClients() throws Exception {
        System.out.println("\n--- Initializing Clients and AIDs ---");
        issuer = initClientAndAid("issuer", "issuerRegistry", "");
        holder = initClientAndAid("holder", "holderRegistry", "");
        legalEntity = initClientAndAid("legalEntity", "legalEntityRegistry", "");
        reeve = initClientAndAid("reeve", "reeveRegistry", reeveIdentifierBran);
        System.out.println("All clients initialized successfully");
    }

    /**
     * Establish communication channels between all clients
     */
    private static void establishCommunicationChannels() throws Exception {
        System.out.println("\n--- Establishing Communication Channels ---");
        
        // Resolve schema OOBIs for all clients
        System.out.println("Resolving schema OOBIs...");
        List<SignifyClient> allClients = List.of(issuer.client(), holder.client(), 
                                                legalEntity.client(), reeve.client());
        List<String> schemaUrls = List.of(QVI_SCHEMA_URL, LE_SCHEMA_URL, REEVE_SCHEMA_URL);
        resolveOobis(allClients, schemaUrls);

        // Establish peer-to-peer communication
        System.out.println("Resolving AID OOBIs for inter-client communication...");
        establishPeerToPeerCommunication();

        // Verify all connections are working
        System.out.println("Verifying client connections...");
        verifyClientConnections();
        
        System.out.println("Communication channels established successfully");
    }

    /**
     * Create and issue the QVI (Qualified vLEI Issuer) credential
     */
    private static String createAndIssueQviCredential() throws Exception {
        System.out.println("\n--- Creating QVI Credential ---");
        String qviCredentialId = createCredential();
        System.out.println("QVI Credential created with ID: " + qviCredentialId);
        
        // Issue credential from issuer to holder
        performCredentialIssuance(qviCredentialId, issuer, holder, "QVI");
        
        return qviCredentialId;
    }

    /**
     * Create and issue the Legal Entity credential (chained to QVI)
     */
    private static String createAndIssueLegalEntityCredential(String qviCredentialId) throws Exception {
        System.out.println("\n--- Creating Legal Entity Credential (Chained) ---");
        String leCredentialId = issueLECredential(qviCredentialId);
        System.out.println("LE Credential created with ID: " + leCredentialId);
        
        // Issue credential from holder to legal entity
        performCredentialIssuance(leCredentialId, holder, legalEntity, "Legal Entity");
        
        return leCredentialId;
    }

    /**
     * Display final results and credential information
     */
    private static void displayFinalResults(String leCredentialId) throws Exception {
        System.out.println("\n--- Final Results ---");
        Object legalEntityCredential = legalEntity.client().credentials().get(leCredentialId);
        System.out.println("Legal Entity Credential Details:");
        System.out.println(legalEntityCredential);
    }


    /**
     * Issue Legal Entity credential chained to QVI credential
     */
    private static String issueLECredential(String qviCredentialId)
            throws Exception {
        System.out.println("Creating chained Legal Entity credential...");
        
        // Get parent QVI credential
        ParentCredentialInfo parentInfo = getParentCredentialInfo(qviCredentialId);
        System.out.println("Parent QVI credential SAID: " + parentInfo.said);
        
        // Build credential subject
        CredentialData.CredentialSubject subject = buildLegalEntitySubject();
        
        // Build vLEI compliance structures
        Map<String, Object> rules = buildVLeiRules();
        Map<String, Object> edge = buildChainedEdge(parentInfo);
        
        // Create and issue credential
        CredentialData credentialData = buildCredentialData(subject, rules, edge, LE_SCHEMA_SAID);
        String leCredentialId = issueCredential(holder, credentialData, "Legal Entity");
        
        System.out.println("Legal Entity Credential ID: " + leCredentialId);
        return leCredentialId;
    }
    
    /**
     * Helper class for parent credential information
     */
    private static class ParentCredentialInfo {
        public final String said;
        public final String schema;
        
        public ParentCredentialInfo(String said, String schema) {
            this.said = said;
            this.schema = schema;
        }
    }
    
    /**
     * Get parent credential information for chaining
     */
    private static ParentCredentialInfo getParentCredentialInfo(String credentialId) throws Exception {
        LinkedHashMap<String, Object> credential = 
                (LinkedHashMap<String, Object>) holder.client().credentials().get(credentialId);
        LinkedHashMap<String, Object> sadBody = 
                (LinkedHashMap<String, Object>) credential.get("sad");
        
        return new ParentCredentialInfo(
            sadBody.get("d").toString(),
            sadBody.get("s").toString()
        );
    }
    
    /**
     * Build Legal Entity credential subject
     */
    private static CredentialData.CredentialSubject buildLegalEntitySubject() {
        // Resolve legal entity contact ID
        String legalEntityContactId = resolveContactWithFallback(
            holder.client(), "legalEntity", legalEntity.aid().prefix);
        
        Map<String, Object> additionalProperties = new LinkedHashMap<>();
        additionalProperties.put("LEI", CFLEI);
        
        CredentialData.CredentialSubject subject = CredentialData.CredentialSubject.builder().build();
        subject.setI(legalEntityContactId);
        subject.setAdditionalProperties(additionalProperties);
        
        return subject;
    }
    
    /**
     * Build vLEI compliance rules
     */
    private static Map<String, Object> buildVLeiRules() {
        Map<String, Object> usageDisclaimer = new LinkedHashMap<>();
        usageDisclaimer.put("l", StringData.USAGE_DISCLAIMER);

        Map<String, Object> issuanceDisclaimer = new LinkedHashMap<>();
        issuanceDisclaimer.put("l", StringData.ISSUANCE_DISCLAIMER);

        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("d", "");
        rules.put("usageDisclaimer", usageDisclaimer);
        rules.put("issuanceDisclaimer", issuanceDisclaimer);
        
        return rules;
    }
    
    /**
     * Build chained edge structure linking to parent credential
     */
    private static Map<String, Object> buildChainedEdge(ParentCredentialInfo parentInfo) {
        Map<String, Object> qvi = new LinkedHashMap<>();
        qvi.put("n", parentInfo.said);  // Parent credential SAID
        qvi.put("s", parentInfo.schema); // Parent credential schema SAID

        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("qvi", qvi);
        edge.put("d", "");
        
        return edge;
    }
    
    /**
     * Build complete credential data structure
     */
    private static CredentialData buildCredentialData(CredentialData.CredentialSubject subject,
            Map<String, Object> rules, Map<String, Object> edge, String schemaSaid) throws Exception {
        // Get registry information
        List<Map<String, Object>> registriesList = 
                (List<Map<String, Object>>) holder.client().registries().list(holder.aid().name());
        String registryKey = registriesList.getFirst().get("regk").toString();
        
        CredentialData credentialData = CredentialData.builder().build();
        credentialData.setA(subject);
        credentialData.setRi(registryKey);
        credentialData.setS(schemaSaid);
        credentialData.setR(rules);
        credentialData.setE(edge);
        
        return credentialData;
    }
    
    /**
     * Issue credential and wait for completion
     */
    private static String issueCredential(ClientAidPair issuer, CredentialData credentialData, 
            String credentialType) throws Exception {
        System.out.println("Issuing " + credentialType + " credential...");
        
        IssueCredentialResult result = issuer.client().credentials().issue(
            issuer.aid().name(), credentialData);
        
        System.out.println("Waiting for " + credentialType + " credential issuance...");
        System.out.println("Operation name: " + result.getOp().getName());
        
        Operation<?> waitOp = issuer.client().operations().wait(result.getOp());
        if (waitOp.getError() != null) {
            throw new IllegalStateException(credentialType + " credential issuance failed: " + 
                                          waitOp.getError());
        }
        
        return result.getAcdc().getKed().get("d").toString();
    }
    
    /**
     * Resolve contact ID with fallback to prefix
     */
    private static String resolveContactWithFallback(SignifyClient client, String alias, String fallbackPrefix) {
        try {
            String contactId = getContactId(client, alias);
            if (contactId != null) {
                System.out.println("Using resolved contact ID for " + alias + ": " + contactId);
                return contactId;
            }
        } catch (Exception e) {
            System.out.println("ERROR resolving contact ID for " + alias + ": " + e.getMessage());
        }
        
        System.out.println("WARNING: Using fallback prefix for " + alias + ": " + fallbackPrefix);
        return fallbackPrefix;
    }


    /**
     * Establish peer-to-peer communication between all clients
     */
    private static void establishPeerToPeerCommunication() throws Exception {
        List<Aid> otherClients = List.of(holder.aid(), legalEntity.aid(), reeve.aid());
        resolveAidOobis(issuer.client, otherClients);
        
        otherClients = List.of(issuer.aid(), legalEntity.aid(), reeve.aid());
        resolveAidOobis(holder.client, otherClients);
        
        otherClients = List.of(issuer.aid(), holder.aid(), reeve.aid());
        resolveAidOobis(legalEntity.client, otherClients);
        
        otherClients = List.of(issuer.aid(), holder.aid(), legalEntity.aid());
        resolveAidOobis(reeve.client, otherClients);
    }

    /**
     * Perform complete credential issuance process (grant + admit)
     */
    private static void performCredentialIssuance(String credentialId, ClientAidPair issuerPair, 
            ClientAidPair holderPair, String credentialType) throws Exception {
        System.out.println("Issuing " + credentialType + " credential from " + 
                          issuerPair.aid().name + " to " + holderPair.aid().name);
        
        // Send IPEX grant
        sendIpexGrant(credentialId, issuerPair.aid(), holderPair.aid(), issuerPair.client());
        
        // Wait for processing
        waitForProcessing(2000);
        
        // Process admit
        processCredentialAdmit(credentialId, holderPair, issuerPair, credentialType);
    }

    /**
     * Process credential admit (refactored from holderAdmitsCredential)
     */
    private static void processCredentialAdmit(String credentialId, ClientAidPair recipient, 
            ClientAidPair issuerPair, String credentialType) throws IOException, InterruptedException {
        System.out.println("Processing " + credentialType + " credential admit");
        
        List<Notification> notifications = waitForNotifications("/exn/ipex/grant", recipient);
        if (notifications == null || notifications.isEmpty()) {
            throw new IllegalStateException("No grant notifications received for " + credentialType);
        }

        Notification grantNotification = notifications.getFirst();
        System.out.println("Processing grant notification: " + grantNotification.a.d);

        try {
            // Build admit arguments
            IpexAdmitArgs admitArgs = buildAdmitArgs(recipient.aid().name, 
                                                   grantNotification.a.d, 
                                                   issuerPair.aid().prefix);
            
            // Execute admit process
            executeAdmitProcess(recipient, issuerPair, admitArgs, grantNotification.i);
            
            System.out.println(credentialType + " credential " + credentialId + 
                             " successfully admitted by " + recipient.aid().name);
        } catch (Exception e) {
            System.err.println("Error during " + credentialType + " credential admit: " + e.getMessage());
            throw new RuntimeException("Failed to admit " + credentialType + " credential", e);
        }
    }

    /**
     * Build IPEX admit arguments
     */
    private static IpexAdmitArgs buildAdmitArgs(String senderName, String grantSaid, String recipient) {
        String dt = new Date().toInstant().toString().replace("Z", "000+00:00");
        
        IpexAdmitArgs args = IpexAdmitArgs.builder().build();
        args.setSenderName(senderName);
        args.setMessage("");
        args.setGrantSaid(grantSaid);
        args.setRecipient(recipient);
        args.setDatetime(dt);
        
        return args;
    }

    /**
     * Execute the admit process (send admit and wait for completion)
     */
    private static void executeAdmitProcess(ClientAidPair recipient, ClientAidPair issuerPair, 
            IpexAdmitArgs admitArgs, String notificationId) throws Exception {
        // Create and submit admit
        Exchanging.ExchangeMessageResult admitResult = recipient.client().ipex().admit(admitArgs);
        Object operation = recipient.client().ipex().submitAdmit(
            recipient.aid().name, 
            admitResult.exn(),
            admitResult.sigs(), 
            admitResult.atc(),
            Collections.singletonList(issuerPair.aid().prefix)
        );
        
        // Wait for operation completion
        Operation<Object> waitOp = recipient.client().operations().wait(Operation.fromObject(operation));
        if (!waitOp.isDone() || waitOp.getError() != null) {
            throw new IllegalStateException("IPEX admit operation failed: " + 
                    (waitOp.getError() != null ? waitOp.getError() : "unknown"));
        }
        
        // Mark notification as read and cleanup
        recipient.client().notifications().mark(notificationId);
        recipient.client().operations().delete(waitOp.getName());
    }

    /**
     * Helper method for consistent wait timing
     */
    private static void waitForProcessing(long milliseconds) throws InterruptedException {
        System.out.println("Waiting " + milliseconds + "ms for processing...");
        Thread.sleep(milliseconds);
    }


    private static List<CreateTestVLei.Notification> waitForNotifications(String route,
            ClientAidPair receiver) throws IOException, InterruptedException {
        int retryCount = 10;
        int waitTimeMs = 3000;

        System.out.println("Waiting for notifications for route: " + route);
        System.out.println("Receiver client prefix: " + receiver.aid().prefix);

        for (int i = 0; i < retryCount; i++) {
            System.out.println(
                    "Checking for notifications, attempt " + (i + 1) + " of " + retryCount);

            try {
                Notifying.Notifications.NotificationListResponse response =
                        receiver.client().notifications().list();
                String notesResponse = response.notes();
                System.out.println("Raw notification response: " + notesResponse);

                List<Notification> receiverNotifications =
                        Utils.fromJson(notesResponse, new TypeReference<>() {});
                System.out.println(receiverNotifications.size() + " total notifications found");

                // Print all notifications for debugging
                for (int j = 0; j < receiverNotifications.size(); j++) {
                    Notification note = receiverNotifications.get(j);
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
     * Create basic QVI credential (legacy method)
     */
    private static String createCredential()
            throws IOException, InterruptedException, DigestException {
        System.out.println("Creating basic QVI credential...");
        
        // Build credential subject
        CredentialData.CredentialSubject subject = CredentialData.CredentialSubject.builder().build();
        subject.setI(holder.aid().prefix);
        subject.setAdditionalProperties(Map.of("LEI", provenantLEI));
        
        // Get registry information
        List<Map<String, Object>> registriesList =
                (List<Map<String, Object>>) issuer.client().registries().list(issuer.aid().name());
        
        // Build credential data
        CredentialData credentialData = CredentialData.builder().build();
        credentialData.setA(subject);
        credentialData.setS(QVI_SCHEMA_SAID);
        credentialData.setRi(registriesList.getFirst().get("regk").toString());

        // Issue and wait for completion
        IssueCredentialResult result = issuer.client().credentials().issue(
            issuer.aid().name(), credentialData);
        issuer.client().operations().wait(result.getOp());
        
        String credentialId = result.getAcdc().getKed().get("d").toString();
        System.out.println("Basic QVI credential created: " + credentialId);
        return credentialId;
    }


    /**
     * Send IPEX grant (refactored from sentIpexGrant)
     */
    private static void sendIpexGrant(String credentialId, Aid issuerAid, Aid recipientAid,
            SignifyClient issuerClient) throws Exception {
        System.out.println("Starting IPEX grant process...");
        System.out.println("  From: " + issuerAid.name + " (" + issuerAid.prefix + ")");
        System.out.println("  To: " + recipientAid.name + " (" + recipientAid.prefix + ")");
        System.out.println("  Credential ID: " + credentialId);

        // Get credential components
        CredentialComponents components = getCredentialComponents(issuerClient, credentialId);
        
        // Resolve recipient contact
        String recipientContactId = resolveRecipientContact(issuerClient, recipientAid);
        
        // Build and send grant
        IpexGrantArgs grantArgs = buildGrantArgs(issuerAid, components, recipientContactId);
        Exchanging.ExchangeMessageResult result = issuerClient.ipex().grant(grantArgs);

        // Submit grant and wait for completion
        submitAndWaitForGrant(issuerClient, issuerAid, result, recipientContactId);
        
        System.out.println("IPEX grant sent successfully!");
    }
    
    /**
     * Helper class to hold credential components
     */
    private static class CredentialComponents {
        public final Map<String, Object> sad;
        public final Map<String, Object> anc;
        public final Map<String, Object> iss;
        
        public CredentialComponents(Map<String, Object> sad, Map<String, Object> anc, Map<String, Object> iss) {
            this.sad = sad;
            this.anc = anc;
            this.iss = iss;
        }
    }
    
    /**
     * Extract credential components from stored credential
     */
    private static CredentialComponents getCredentialComponents(SignifyClient client, String credentialId) 
            throws Exception {
        Object credential = client.credentials().get(credentialId);
        LinkedHashMap<String, Object> credentialMap = (LinkedHashMap<String, Object>) credential;
        
        @SuppressWarnings("unchecked")
        Map<String, Object> sad = (Map<String, Object>) credentialMap.get("sad");
        @SuppressWarnings("unchecked")
        Map<String, Object> anc = (Map<String, Object>) credentialMap.get("anc");
        @SuppressWarnings("unchecked")
        Map<String, Object> iss = (Map<String, Object>) credentialMap.get("iss");
        
        return new CredentialComponents(sad, anc, iss);
    }
    
    /**
     * Resolve recipient contact ID with fallback
     */
    private static String resolveRecipientContact(SignifyClient issuerClient, Aid recipientAid) {
        try {
            String contactId = getContactId(issuerClient, recipientAid.name);
            if (contactId != null) {
                System.out.println("Using resolved contact ID: " + contactId);
                return contactId;
            }
        } catch (Exception e) {
            System.out.println("ERROR resolving contact ID: " + e.getMessage());
        }
        
        System.out.println("WARNING: Using original prefix as fallback: " + recipientAid.prefix);
        return recipientAid.prefix;
    }
    
    /**
     * Build IPEX grant arguments
     */
    private static IpexGrantArgs buildGrantArgs(Aid issuerAid, CredentialComponents components, 
            String recipientContactId) {
        String dt = new Date().toInstant().toString().replace("Z", "000+00:00");
        
        IpexGrantArgs gArgs = IpexGrantArgs.builder().build();
        gArgs.setSenderName(issuerAid.name);
        gArgs.setAcdc(new Serder(components.sad));
        gArgs.setAnc(new Serder(components.anc));
        gArgs.setIss(new Serder(components.iss));
        gArgs.setAncAttachment(null);
        gArgs.setRecipient(recipientContactId);
        gArgs.setDatetime(dt);
        
        return gArgs;
    }
    
    /**
     * Submit grant and wait for completion
     */
    private static void submitAndWaitForGrant(SignifyClient issuerClient, Aid issuerAid, 
            Exchanging.ExchangeMessageResult result, String recipientContactId) 
            throws IOException, InterruptedException {
        System.out.println("Submitting IPEX grant...");
        
        Object operation = issuerClient.ipex().submitGrant(
            issuerAid.name, 
            result.exn(), 
            result.sigs(),
            result.atc(), 
            Collections.singletonList(recipientContactId)
        );

        System.out.println("Waiting for IPEX grant operation to complete...");
        Operation<Object> waitOp = issuerClient.operations().wait(Operation.fromObject(operation));
        
        if (!waitOp.isDone() || waitOp.getError() != null) {
            throw new IllegalStateException("IPEX grant operation failed: " +
                    (waitOp.getError() != null ? waitOp.getError() : "unknown"));
        }
        
        issuerClient.operations().delete(waitOp.getName());
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
        List<Contacting.Contact> issuerContacts =
                Arrays.asList(issuer.client().contacts().list(null, null, null));
        List<Contacting.Contact> holderContacts =
                Arrays.asList(holder.client().contacts().list(null, null, null));
        List<Contacting.Contact> legalEntityContacts =
                Arrays.asList(legalEntity.client().contacts().list(null, null, null));
        List<Contacting.Contact> reeveContacts =
                Arrays.asList(reeve.client().contacts().list(null, null, null));
        // Verify specific contact exists by name rather than prefix (since prefix might be
        // different after OOBI resolution)
        boolean issuerKnowsHolder =
                issuerContacts.stream().anyMatch(c -> "holder".equals(c.getAlias()));
        boolean holderKnowsIssuer =
                holderContacts.stream().anyMatch(c -> "issuer".equals(c.getAlias()));
        boolean legalEntityKnowsHolder =
                legalEntityContacts.stream().anyMatch(c -> "holder".equals(c.getAlias()));
        boolean holderKnowsLegalEntity =
                holderContacts.stream().anyMatch(c -> "legalEntity".equals(c.getAlias()));
        boolean reeveKnowsHolder =
                reeveContacts.stream().anyMatch(c -> "holder".equals(c.getAlias()));
        boolean holderKnowsReeve =
                holderContacts.stream().anyMatch(c -> "reeve".equals(c.getAlias()));
        if (!issuerKnowsHolder || !holderKnowsIssuer || !legalEntityKnowsHolder
                || !holderKnowsLegalEntity || !reeveKnowsHolder || !holderKnowsReeve) {
            System.out.println("WARNING: Not all contacts are properly established by alias!");

            // Try by prefix as fallback
            boolean issuerKnowsHolderByPrefix =
                    issuerContacts.stream().anyMatch(c -> holder.aid().prefix.equals(c.getId()));
            boolean holderKnowsIssuerByPrefix =
                    holderContacts.stream().anyMatch(c -> issuer.aid().prefix.equals(c.getId()));
            System.out.println(
                    "Fallback - Issuer knows holder (by prefix): " + issuerKnowsHolderByPrefix);
            System.out.println(
                    "Fallback - Holder knows issuer (by prefix): " + holderKnowsIssuerByPrefix);
        } else {
            System.out.println("All contacts verified successfully by alias!");
        }
    }

    public static String getContactId(SignifyClient client, String alias) throws Exception {
        List<Contacting.Contact> contacts =
                Arrays.asList(client.contacts().list(null, "alias", "^" + alias + "$"));
        if (!contacts.isEmpty()) {
            return contacts.getFirst().getId();
        }
        return null;
    }

    public static class StringData {
        public static final String USAGE_DISCLAIMER =
                "Usage of a valid, unexpired, and non-revoked vLEI Credential, as defined in the associated Ecosystem Governance Framework, does not assert that the Legal Entity is trustworthy, honest, reputable in its business dealings, safe to do business with, or compliant with any laws or that an implied or expressly intended purpose will be fulfilled.";
        public static final String ISSUANCE_DISCLAIMER =
                "All information in a valid, unexpired, and non-revoked vLEI Credential, as defined in the associated Ecosystem Governance Framework, is accurate as of the date the validation process was complete. The vLEI Credential has been issued to the legal entity or person named in the vLEI Credential as the subject; and the qualified vLEI Issuer exercised reasonable care to perform the validation process set forth in the vLEI Ecosystem Governance Framework.";
    }
}
