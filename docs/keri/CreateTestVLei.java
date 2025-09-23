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
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.cardanofoundation.signify.core.States;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;



public class CreateTestVLei {

    // Type definition for Aid
    private record Aid(String name, String prefix, String oobi) {
    }
    private record ClientAidPair(SignifyClient client, Aid aid) {
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
                List.of(QVI_SCHEMA_URL, LE_SCHEMA_URL, REEVE_SCHEMA_URL));

        System.out.println("Creating holder credential");

        List<Map<String, Object>> registriesList =
                (List<Map<String, Object>>) issuerClient.registries().list(issuerAid.name);

        CredentialData.CredentialSubject a = CredentialData.CredentialSubject.builder().build();
        a.setI(holderAid.prefix);
        a.setAdditionalProperties(Map.of("LEI", provenantLEI));
        CredentialData cData = CredentialData.builder().build();
        cData.setA(a);
        cData.setS(QVI_SCHEMA_SAID);
        cData.setI(issuerAid.prefix);
        cData.setRi(registriesList.getFirst().get("regk").toString()); // TODO need to check this

        IssueCredentialResult holderCredentialResult =
                issuerClient.credentials().issue(issuerAid.name(), cData);
        Operation<?> wait = issuerClient.operations().wait(holderCredentialResult.getOp());
        String qviCredentialId = holderCredentialResult.getAcdc().getKed().get("d").toString();
        System.out.println("QVI Credential ID: " + qviCredentialId);
        System.out.println(wait);
        System.out.println("IPEX grant - Sending Credential to Holder");
        String dt = new Date().toInstant().toString().replace("Z", "000+00:00");
        Object issuerCredential = null;
        int retries = 10;
        int delayMillis = 2000;
        for (int i = 0; i < retries; i++) {
            try {
            issuerCredential = issuerClient.credentials().get(qviCredentialId);
            if (issuerCredential != null) {
                break;
            }
            } catch (Exception e) {
                System.out.println("Retrying to get issuer credential... attempt " + (i + 1));
            }
            Thread.sleep(delayMillis);
        }
        if (issuerCredential == null) {
            throw new IllegalStateException("Issuer credential not available after retries");
        }
        LinkedHashMap<String, Object> issuerCredentialList =
                (LinkedHashMap<String, Object>) issuerCredential;
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
        if(!wait2.isDone()) {
            throw new IllegalStateException("Operation not done yet");
        }
        System.out.println(wait2.getResponse());
        issuerClient.operations().delete(wait2.getName());
        System.out.println("Holder IPEX admit");
        Notifying.Notifications.NotificationListResponse response = holderClient.notifications().list();
        String notesResponse = response.notes();
        List<Map<String, Object>> holderNotifications = objectMapper.readValue(notesResponse, new TypeReference<>() {});
        Map<String, Object> grantNotification = holderNotifications.getFirst();

        IpexAdmitArgs iargs = IpexAdmitArgs.builder().build();
        iargs.setSenderName(holderAid.name);
        iargs.setMessage("");
        iargs.setGrantSaid((String)((Map<String,Object>)grantNotification.get("a")).get("d"));
        iargs.setRecipient(issuerAid.prefix);
        iargs.setDatetime(dt);

        Exchanging.ExchangeMessageResult resultApexAdmint = holderClient.ipex().admit(iargs);
        op = holderClient.ipex().submitAdmit(holderAid.name, resultApexAdmint.exn(), 
                resultApexAdmint.sigs(),
                resultApexAdmint.atc(), Collections.singletonList(issuerAid.prefix));
        holderClient.operations().wait(Operation.fromObject(op));
        System.out.println("Holder Credential: " + qviCredentialId + " issued to " + holderAid.name);
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
                System.out.println("Resolving OOBI for " + oobi);
                Object result = client.oobis().resolve(oobi, null);
                client.operations().wait(Operation.fromObject(result));
            }
        }
    }

    public static void createRegistry(SignifyClient client, Aid aid, String registryName) {
        CreateRegistryArgs registryArgs =
                CreateRegistryArgs.builder().name(aid.name).registryName(registryName).build();
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
