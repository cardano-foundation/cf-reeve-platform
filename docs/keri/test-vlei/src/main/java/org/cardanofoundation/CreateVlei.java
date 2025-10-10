package org.cardanofoundation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cardanofoundation.domain.Aid;
import org.cardanofoundation.domain.ClientAidPair;
import org.cardanofoundation.domain.CredentialComponents;
import org.cardanofoundation.domain.CredentialInfo;
import org.cardanofoundation.domain.CredentialSerializationData;
import org.cardanofoundation.domain.CredentialType;
import org.cardanofoundation.domain.EventDataAndAttachement;
import org.cardanofoundation.domain.Notification;
import org.cardanofoundation.domain.ParentCredentialInfo;
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

public class CreateVlei {

    private static ClientAidPair gleif, qvi, legalEntity, reeve;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void createVlei(String fileName) {
        System.out.println("=== vLEI Credential Chain Setup Starting ===");

        try {
            // Step 1: Initialize all clients and identifiers
            initializeClients();

            // Step 2: Establish communication channels
            establishCommunicationChannels();

            // Step 3: Create the complete credential chain
            List<CredentialInfo> credentialChain = createCredentialChain();

            // Step 4: Display chain results
            displayCredentialChain(credentialChain);

            System.out.println("=== vLEI Credential Chain Setup Complete ===");

            System.out.println("Decentralization Information:");
            System.out.println("Reeve AID Prefix: " + reeve.aid().prefix());
            System.out.println("Reeve AID OOBI: " + reeve.aid().oobi());

            Optional<Object> credential = reeve.client().credentials().get(credentialChain.get(2).id(), true);
            System.out.println("Reeve Credential (raw): " + credential);
            String credentialCesr = (String) credential.orElseThrow();
            List<Map<String, Object>> cesrData = parseCESRData(credentialCesr);
            System.out.println("Reeve Credential CESR Data: " + cesrData);
            List<Map<String, Object>> allVcpEvents = new ArrayList<>();
            List<String> allVcpAttachments = new ArrayList<>();
            List<Map<String, Object>> allIssEvents = new ArrayList<>();
            List<String> allIssAttachments = new ArrayList<>();
            List<Map<String, Object>> allAcdcEvents = new ArrayList<>();
            for (Map<String, Object> eventData : cesrData) {
                Map<String, Object> event = (Map<String, Object>) eventData.get("event");
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
                    if (event.containsKey("s") && event.containsKey("a")
                            && event.containsKey("i")) {
                        Object schemaObj = event.get("s");
                        if (schemaObj != null) {
                            allAcdcEvents.add(event);
                        }
                    }
                }
            }
            System.out.println("All VCP Events: " + allVcpEvents);
            CredentialSerializationData decentralizationInfo = new CredentialSerializationData(List.of(gleif.aid().prefix(),qvi.aid().prefix(), legalEntity.aid().prefix(), reeve.aid().prefix()),
                    new EventDataAndAttachement(allVcpEvents, allVcpAttachments),
                    new EventDataAndAttachement(allIssEvents, allIssAttachments), allAcdcEvents);
            String credentialSerializationData = objectMapper.writeValueAsString(decentralizationInfo);
            Files.writeString(Path.of(fileName), credentialSerializationData);

            List<String> oobList = Arrays.asList(
                    gleif.aid().oobi(),
                    qvi.aid().oobi(),
                    legalEntity.aid().oobi(),
                    reeve.aid().oobi());
            String oobis = objectMapper.writeValueAsString(oobList);
            Files.writeString(Path.of("oobis.json"), oobis);
        } catch (Exception e) {
            System.err.println("ERROR: Credential chain setup failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    

    // ============================================================================
    // CREDENTIAL CHAIN ORCHESTRATION
    // ============================================================================

    /**
     * Get parent credential information for chaining
     */
    @SuppressWarnings("unchecked")
    private static ParentCredentialInfo getParentCredentialInfo(String credentialId,
            SignifyClient client) throws Exception {
        LinkedHashMap<String, Object> credential =
                (LinkedHashMap<String, Object>)((Optional<Object>) client.credentials().get(credentialId)).get();
        LinkedHashMap<String, Object> sadBody =
                (LinkedHashMap<String, Object>) credential.get("sad");

        return new ParentCredentialInfo(sadBody.get("d").toString(), sadBody.get("s").toString());
    }

    /**
     * Creates the complete credential chain: Issuer → Holder → LegalEntity → Reeve
     */
    private static List<CredentialInfo> createCredentialChain() throws Exception {
        System.out.println("\n--- Creating Complete Credential Chain ---");
        List<CredentialInfo> chain = new ArrayList<>();

        // Step 1: Root QVI credential (Issuer → Holder)
        CredentialInfo qviCredential = createRootCredential();
        chain.add(qviCredential);

        // Step 2: Legal Entity credential (Holder → LegalEntity, chained to QVI)
        CredentialInfo leCredential = createChainedCredential(qviCredential, qvi, legalEntity,
                Constants.LE_SCHEMA_SAID, CredentialType.LEGAL_ENTITY, Constants.CFLEI);
        chain.add(leCredential);

        // Step 3: Reeve credential (LegalEntity → Reeve, chained to LE)
        CredentialInfo reeveCredential = createChainedCredential(leCredential, legalEntity, reeve,
                Constants.REEVE_SCHEMA_SAID, CredentialType.REEVE, Constants.REEVE_LEI);
        chain.add(reeveCredential);

        System.out.println(
                "Credential chain created successfully with " + chain.size() + " credentials");
        return chain;
    }

    /**
     * Creates the root QVI credential (first in chain)
     */
    private static CredentialInfo createRootCredential() throws Exception {
        System.out.println("Creating root QVI credential...");
        String credentialId = createAndIssueQviCredential();
        return new CredentialInfo(credentialId, 
                Constants.QVI_SCHEMA_SAID, gleif, qvi, CredentialType.QVI);
    }

    /**
     * Creates a chained credential linked to its parent
     */
    private static CredentialInfo createChainedCredential(CredentialInfo parentCredential,
            ClientAidPair issuerPair, ClientAidPair holderPair, String schemaSaid,
            CredentialType credentialType, String lei) throws Exception {
        System.out.println("Creating " + credentialType + " credential (chained to "
                + parentCredential.type() + ")...");

        String credentialId = createAndIssueChainedCredential(parentCredential.id(), issuerPair,
                holderPair, schemaSaid, credentialType, lei);

        return new CredentialInfo(credentialId, schemaSaid, issuerPair, holderPair, credentialType);
    }

    /**
     * Displays the complete credential chain information
     */
    private static void displayCredentialChain(List<CredentialInfo> chain) throws Exception {
        System.out.println("\n--- Credential Chain Summary ---");

        for (int i = 0; i < chain.size(); i++) {
            CredentialInfo cred = chain.get(i);
            System.out.println((i + 1) + ". " + cred.type() + " Credential:");
            System.out.println("   ID: " + cred.id());
            System.out.println("   Schema: " + cred.schema());
            System.out.println("   Issuer: " + cred.issuer().aid().name());
            System.out.println("   Holder: " + cred.holder().aid().name());

            // Display credential details
            Object credentialData = cred.holder().client().credentials().get(cred.id());
            System.out.println("   Details: " + credentialData);
            System.out.println();
        }
    }

    /**
     * Creates and issues a chained credential linking to parent credential
     */
    private static String createAndIssueChainedCredential(String parentCredentialId,
            ClientAidPair issuerPair, ClientAidPair holderPair, String schemaSaid,
            CredentialType credentialType, String lei) throws Exception {
        System.out.println("Creating chained " + credentialType + " credential...");

        // Get parent credential information
        ParentCredentialInfo parentInfo =
                getParentCredentialInfo(parentCredentialId, issuerPair.client());
        System.out.println("Parent credential SAID: " + parentInfo.said);

        // Build credential subject
        CredentialData.CredentialSubject subject =
                buildCredentialSubject(issuerPair.client(), holderPair.aid(), lei, credentialType);

        // Build vLEI compliance structures
        Map<String, Object> rules = buildVLeiRules();
        Map<String, Object> edge = buildChainedEdge(parentInfo, credentialType);

        // Create and issue credential
        CredentialData credentialData = buildCredentialData(subject, rules, edge, schemaSaid,
                issuerPair.client(), issuerPair.aid(), holderPair.rulesNeeded());
        String credentialId = issueCredential(issuerPair, credentialData, credentialType);

        // Perform complete issuance process (grant + admit)
        performCredentialIssuance(credentialId, issuerPair, holderPair, credentialType);

        System.out.println(credentialType + " Credential ID: " + credentialId);
        return credentialId;
    }

    // ============================================================================
    // CLIENT INITIALIZATION AND SETUP
    // ============================================================================

    /**
     * Initialize all client identifiers and registries
     */
    private static void initializeClients() throws Exception {
        System.out.println("\n--- Initializing Clients and AIDs ---");
        gleif = initClientAndAid("gleif", "gleifRegistry", "", true);
        qvi = initClientAndAid("qvi", "qviRegistry", "", true);
        legalEntity = initClientAndAid("legalEntity", "legalEntityRegistry", "", true);
        reeve = initClientAndAid("reeve", "reeveRegistry11", Constants.reeveIdentifierBran, false);
        System.out.println("All clients initialized successfully");
    }

    // ============================================================================
    // COMMUNICATION CHANNEL SETUP
    // ============================================================================

    /**
     * Establish communication channels between all clients
     */
    private static void establishCommunicationChannels() throws Exception {
        System.out.println("\n--- Establishing Communication Channels ---");

        // Resolve schema OOBIs for all clients
        System.out.println("Resolving schema OOBIs...");
        List<SignifyClient> allClients =
                List.of(gleif.client(), qvi.client(), legalEntity.client(), reeve.client());
        List<String> schemaUrls = List.of(
                Constants.QVI_SCHEMA_URL, Constants.LE_SCHEMA_URL, Constants.REEVE_SCHEMA_URL);
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
        performCredentialIssuance(qviCredentialId, gleif, qvi, CredentialType.QVI);

        return qviCredentialId;
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
    private static String issueLECredential(String qviCredentialId) throws Exception {
        System.out.println("Creating chained Legal Entity credential...");

        // Get parent QVI credential
        ParentCredentialInfo parentInfo = getParentCredentialInfo(qviCredentialId, qvi.client());
        System.out.println("Parent QVI credential SAID: " + parentInfo.said);

        // Build credential subject
        CredentialData.CredentialSubject subject = buildLegalEntitySubject();

        // Build vLEI compliance structures
        Map<String, Object> rules = buildVLeiRules();
        Map<String, Object> edge = buildChainedEdge(parentInfo, CredentialType.LEGAL_ENTITY);

        // Create and issue credential
        CredentialData credentialData = buildCredentialData(subject, rules, edge, 
                Constants.LE_SCHEMA_SAID);
        String leCredentialId = issueCredential(qvi, credentialData, CredentialType.LEGAL_ENTITY);

        System.out.println("Legal Entity Credential ID: " + leCredentialId);
        return leCredentialId;
    }

    private static CredentialData.CredentialSubject buildLegalEntitySubject() {
        // Resolve legal entity contact ID
        String legalEntityContactId = legalEntity.aid().prefix();

        Map<String, Object> additionalProperties = new LinkedHashMap<>();
        additionalProperties.put("LEI", Constants.CFLEI);

        CredentialData.CredentialSubject subject =
                CredentialData.CredentialSubject.builder().build();
        subject.setI(legalEntityContactId);
        subject.setAdditionalProperties(additionalProperties);

        return subject;
    }

    private static CredentialData.CredentialSubject buildCredentialSubject(
            SignifyClient issuerClient, Aid recipientAid, String lei,
            CredentialType credentialType) {
        // Resolve recipient contact ID
        // String recipientContactId = resolveContactWithFallback(issuerClient, recipientAid.name,
        // recipientAid.prefix);

        Map<String, Object> additionalProperties = new LinkedHashMap<>();
        additionalProperties.put("LEI", lei);

        CredentialData.CredentialSubject subject =
                CredentialData.CredentialSubject.builder().build();
        subject.setI(resolveRecipientContact(issuerClient, recipientAid));
        if (!credentialType.equals(CredentialType.REEVE)) {
            subject.setAdditionalProperties(additionalProperties);
        }
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
    private static Map<String, Object> buildChainedEdge(ParentCredentialInfo parentInfo,
            CredentialType credentialType) {
        Map<String, Object> qvi = new LinkedHashMap<>();
        qvi.put("n", parentInfo.said); // Parent credential SAID
        qvi.put("s", parentInfo.schema); // Parent credential schema SAID

        Map<String, Object> edge = new LinkedHashMap<>();
        if (credentialType.equals(CredentialType.REEVE)) {
            edge.put("le", qvi);
        } else {
            edge.put("qvi", qvi);
        }
        edge.put("d", "");

        return edge;
    }

    /**
     * Build complete credential data structure
     */
    @SuppressWarnings("unchecked")
    private static CredentialData buildCredentialData(CredentialData.CredentialSubject subject,
            Map<String, Object> rules, Map<String, Object> edge, String schemaSaid,
            SignifyClient client, Aid issuerAid, boolean rulesNeeded) throws Exception {
        // Get registry information
        List<Map<String, Object>> registriesList =
                (List<Map<String, Object>>) client.registries().list(issuerAid.name());
        String registryKey = registriesList.getFirst().get("regk").toString();

        CredentialData credentialData = CredentialData.builder().build();
        credentialData.setA(subject);
        credentialData.setRi(registryKey);
        credentialData.setS(schemaSaid);
        if (rulesNeeded) {
            credentialData.setR(rules);

        }
        credentialData.setE(edge);


        return credentialData;
    }

    /**
     * Build complete credential data structure (legacy method for backward compatibility)
     */
    @SuppressWarnings("unchecked")
    private static CredentialData buildCredentialData(CredentialData.CredentialSubject subject,
            Map<String, Object> rules, Map<String, Object> edge, String schemaSaid)
            throws Exception {
        // Get registry information
        List<Map<String, Object>> registriesList =
                (List<Map<String, Object>>) qvi.client().registries().list(qvi.aid().name());
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
            CredentialType credentialType) throws Exception {
        System.out.println("Issuing " + credentialType + " credential...");

        IssueCredentialResult result =
                issuer.client().credentials().issue(issuer.aid().name(), credentialData);

        System.out.println("Waiting for " + credentialType + " credential issuance...");
        System.out.println("Operation name: " + result.getOp().getName());

        Operation<?> waitOp = issuer.client().operations().wait(result.getOp());
        if (waitOp.getError() != null) {
            throw new IllegalStateException(
                    credentialType + " credential issuance failed: " + waitOp.getError());
        }

        return result.getAcdc().getKed().get("d").toString();
    }

    /**
     * Establish peer-to-peer communication between all clients
     */
    private static void establishPeerToPeerCommunication() throws Exception {
        List<Aid> otherClients = List.of(qvi.aid(), legalEntity.aid(), reeve.aid());
        resolveAidOobis(gleif.client(), otherClients);

        otherClients = List.of(gleif.aid(), legalEntity.aid(), reeve.aid());
        resolveAidOobis(qvi.client(), otherClients);

        otherClients = List.of(gleif.aid(), qvi.aid(), reeve.aid());
        resolveAidOobis(legalEntity.client(), otherClients);

        otherClients = List.of(gleif.aid(), qvi.aid(), legalEntity.aid());
        resolveAidOobis(reeve.client(), otherClients);
    }

    /**
     * Perform complete credential issuance process (grant + admit)
     */
    private static void performCredentialIssuance(String credentialId, ClientAidPair issuerPair,
            ClientAidPair holderPair, CredentialType credentialType) throws Exception {
        System.out.println("Issuing " + credentialType + " credential from " + issuerPair.aid().name()
                + " to " + holderPair.aid().name());

        // Send IPEX grant
        sendIpexGrant(credentialId, issuerPair.aid(), holderPair.aid(), issuerPair.client());

        // Process admit
        processCredentialAdmit(credentialId, holderPair, issuerPair, credentialType);
    }

    /**
     * Process credential admit (refactored from holderAdmitsCredential)
     */
    private static void processCredentialAdmit(String credentialId, ClientAidPair recipient,
            ClientAidPair issuerPair, CredentialType credentialType)
            throws IOException, InterruptedException {
        System.out.println("Processing " + credentialType + " credential admit");

        List<Notification> notifications = waitForNotifications("/exn/ipex/grant", recipient);
        if (notifications == null || notifications.isEmpty()) {
            throw new IllegalStateException(
                    "No grant notifications received for " + credentialType);
        }

        Notification grantNotification = notifications.getFirst();
        System.out.println("Processing grant notification: " + grantNotification.a.d);

        try {
            // Build admit arguments
            IpexAdmitArgs admitArgs = buildAdmitArgs(recipient.aid().name(), grantNotification.a.d,
                    issuerPair.aid().prefix());

            // Execute admit process
            executeAdmitProcess(recipient, issuerPair, admitArgs, grantNotification.i);

            System.out.println(credentialType + " credential " + credentialId
                    + " successfully admitted by " + recipient.aid().name());
        } catch (Exception e) {
            System.err.println(
                    "Error during " + credentialType + " credential admit: " + e.getMessage());
            throw new RuntimeException("Failed to admit " + credentialType + " credential", e);
        }
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

    /**
     * Execute the admit process (send admit and wait for completion)
     */
    private static void executeAdmitProcess(ClientAidPair recipient, ClientAidPair issuerPair,
            IpexAdmitArgs admitArgs, String notificationId) throws Exception {
        // Create and submit admit
        Exchanging.ExchangeMessageResult admitResult = recipient.client().ipex().admit(admitArgs);
        Object operation = recipient.client().ipex().submitAdmit(recipient.aid().name(),
                admitResult.exn(), admitResult.sigs(), admitResult.atc(),
                Collections.singletonList(issuerPair.aid().prefix()));

        // Wait for operation completion
        Operation<Object> waitOp =
                recipient.client().operations().wait(Operation.fromObject(operation));
        if (!waitOp.isDone() || waitOp.getError() != null) {
            throw new IllegalStateException("IPEX admit operation failed: "
                    + (waitOp.getError() != null ? waitOp.getError() : "unknown"));
        }

        // Mark notification as read and cleanup
        recipient.client().notifications().mark(notificationId);
        recipient.client().operations().delete(waitOp.getName());
    }

    private static List<Notification> waitForNotifications(String route,
            ClientAidPair receiver) throws IOException, InterruptedException {
        int retryCount = 10;
        int waitTimeMs = 3000;

        System.out.println("Waiting for notifications for route: " + route);
        System.out.println("Receiver client prefix: " + receiver.aid().prefix());

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
        CredentialData.CredentialSubject subject =
                CredentialData.CredentialSubject.builder().build();
        subject.setI(resolveRecipientContact(gleif.client(), qvi.aid()));
        subject.setAdditionalProperties(Map.of("LEI", Constants.provenantLEI));

        // Get registry information
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> registriesList =
                (List<Map<String, Object>>) gleif.client().registries().list(gleif.aid().name());

        // Build credential data
        CredentialData credentialData = CredentialData.builder().build();
        credentialData.setA(subject);
        credentialData.setS(Constants.QVI_SCHEMA_SAID);
        credentialData.setRi(registriesList.getFirst().get("regk").toString());

        // Issue and wait for completion
        IssueCredentialResult result =
                gleif.client().credentials().issue(gleif.aid().name(), credentialData);
        gleif.client().operations().wait(result.getOp());

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
        System.out.println("  From: " + issuerAid.name() + " (" + issuerAid.prefix() + ")");
        System.out.println("  To: " + recipientAid.name() + " (" + recipientAid.prefix() + ")");
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
     * Extract credential components from stored credential
     */
    @SuppressWarnings("unchecked")
    private static CredentialComponents getCredentialComponents(SignifyClient client,
            String credentialId) throws Exception {
        Object credential = client.credentials().get(credentialId);
        LinkedHashMap<String, Object> credentialMap = ((Optional<LinkedHashMap<String, Object>>) credential).orElseThrow();

        Map<String, Object> sad = (Map<String, Object>) credentialMap.get("sad");
        Map<String, Object> anc = (Map<String, Object>) credentialMap.get("anc");
        Map<String, Object> iss = (Map<String, Object>) credentialMap.get("iss");

        return new CredentialComponents(sad, anc, iss);
    }

    /**
     * Resolve recipient contact ID with fallback
     */
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

    /**
     * Build IPEX grant arguments
     */
    private static IpexGrantArgs buildGrantArgs(Aid issuerAid, CredentialComponents components,
            String recipientContactId) {
        String dt = new Date().toInstant().toString().replace("Z", "000+00:00");

        IpexGrantArgs gArgs = IpexGrantArgs.builder().build();
        gArgs.setSenderName(issuerAid.name());
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

        Object operation = issuerClient.ipex().submitGrant(issuerAid.name(), result.exn(),
                result.sigs(), result.atc(), Collections.singletonList(recipientContactId));

        System.out.println("Waiting for IPEX grant operation to complete...");
        Operation<Object> waitOp = issuerClient.operations().wait(Operation.fromObject(operation));

        if (!waitOp.isDone() || waitOp.getError() != null) {
            throw new IllegalStateException("IPEX grant operation failed: "
                    + (waitOp.getError() != null ? waitOp.getError() : "unknown"));
        }

        issuerClient.operations().delete(waitOp.getName());
    }


    // ============================================================================
    // CORE KERI/SIGNIFY UTILITY METHODS
    // ============================================================================

    /**
     * Initialize a Signify client and create AID with registry
     */
    public static ClientAidPair initClientAndAid(String name, String registryName, String bran,
            boolean rulesNeeded) throws Exception {
        SignifyClient client = UtilFunctions.getOrCreateClient(bran);
        Aid aid = createAid(client, name);
        createRegistry(client, aid, registryName);
        return new ClientAidPair(client, aid, rulesNeeded);
    }

    

    public static Aid createAid(SignifyClient client, String name) throws Exception {
        Object id = null;
        String eid = "";
        try {
            States.HabState identifier = client.identifiers().get(name).orElseThrow();
            id = identifier.getPrefix();
        } catch (Exception e) {
            CreateIdentifierArgs kArgs = CreateIdentifierArgs.builder().build();
            kArgs.setToad(Constants.witnessIds.size());
            kArgs.setWits(Constants.witnessIds);
            EventResult result = client.identifiers().create(name, kArgs);
            Object op = client.operations().wait(Operation.fromObject(result.op()));
            if (op instanceof String) {
                try {
                    @SuppressWarnings("unchecked")
                    HashMap<String, Object> map =
                            objectMapper.readValue((String) op, HashMap.class);
                    @SuppressWarnings("unchecked")
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
        @SuppressWarnings("unchecked")
        String getOobi = ((Optional<LinkedHashMap<String, Object>>) oobi).orElseThrow().get("oobis").toString()
                .replaceAll("[\\[\\]]", "");
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

    public static RegistryResult createRegistry(SignifyClient client, Aid aid, String registryName) {
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

    public static void verifyClientConnections() throws Exception {
        List<Contacting.Contact> issuerContacts =
                Arrays.asList(gleif.client().contacts().list(null, null, null));
        List<Contacting.Contact> holderContacts =
                Arrays.asList(qvi.client().contacts().list(null, null, null));
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
                    issuerContacts.stream().anyMatch(c -> qvi.aid().prefix().equals(c.getId()));
            boolean holderKnowsIssuerByPrefix =
                    holderContacts.stream().anyMatch(c -> gleif.aid().prefix().equals(c.getId()));
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

    /**
     * Parses CESR format string into an array of events with their attachments CESR format:
     * {json_event}{attachment}{json_event}{attachment}...
     * 
     * @param cesrData The CESR format string
     * @return List of maps containing "event" and "atc" keys
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> parseCESRData(String cesrData) {
        List<Map<String, Object>> result = new ArrayList<>();

        int index = 0;
        while (index < cesrData.length()) {
            // Find the start of JSON event (look for opening brace)
            if (cesrData.charAt(index) == '{') {
                // Find the end of JSON event by counting braces
                int braceCount = 0;
                int jsonStart = index;
                int jsonEnd = index;

                for (int i = index; i < cesrData.length(); i++) {
                    char ch = cesrData.charAt(i);
                    if (ch == '{') {
                        braceCount++;
                    } else if (ch == '}') {
                        braceCount--;
                        if (braceCount == 0) {
                            jsonEnd = i + 1;
                            break;
                        }
                    }
                }

                // Extract JSON event
                String jsonEvent = cesrData.substring(jsonStart, jsonEnd);

                // Find attachment data (everything until next '{' or end of string)
                int attachmentStart = jsonEnd;
                int attachmentEnd = cesrData.length();

                for (int i = attachmentStart; i < cesrData.length(); i++) {
                    if (cesrData.charAt(i) == '{') {
                        attachmentEnd = i;
                        break;
                    }
                }

                String attachment = "";
                if (attachmentStart < attachmentEnd) {
                    attachment = cesrData.substring(attachmentStart, attachmentEnd);
                }

                // Parse JSON event to Object
                try {
                    Map<String, Object> eventObj = Utils.fromJson(jsonEvent, Map.class);

                    Map<String, Object> eventMap = new LinkedHashMap<>();
                    eventMap.put("event", eventObj);
                    eventMap.put("atc", attachment);
                    result.add(eventMap);
                } catch (Exception e) {
                    System.err.println("Failed to parse JSON event: " + jsonEvent);
                    e.printStackTrace();
                }

                index = attachmentEnd;
            } else {
                index++;
            }
        }

        return result;
    }
}
