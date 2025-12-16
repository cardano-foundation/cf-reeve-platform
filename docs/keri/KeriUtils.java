import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Arrays;

import org.cardanofoundation.signify.app.Contacting;
import org.cardanofoundation.signify.app.aiding.CreateIdentifierArgs;
import org.cardanofoundation.signify.app.aiding.EventResult;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Coring;
import org.cardanofoundation.signify.app.coring.Operation;
import org.cardanofoundation.signify.cesr.Salter;
import org.cardanofoundation.signify.core.States;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Utility class for common KERI operations
 */
public class KeriUtils {

    private static final String MISCONFIGURED_AGENT_CONFIGURATION = "Agent configuration is missing iurls";
    private static final String INSUFFICIENT_WITNESSES_AVAILABLE = "Insufficient witnesses available";

    // ------- Records ------- //

    public static record Aid(String name, String prefix, String oobi) {}

    public static record WitnessInfo(String eid, String oobi) {}

    public static record AvailableWitnesses(int toad, List<WitnessInfo> witnesses) {}

    // ------- Client Operations ------- //

    /**
     * Get or create a Signify client with the given passcode
     */
    public static SignifyClient getOrCreateClient(String keriUrl, String keriBootUrl, String bran) throws Exception {
        if (bran == null || bran.isEmpty()) {
            bran = Coring.randomPasscode();
        }

        SignifyClient client = new SignifyClient(
                keriUrl, bran, Salter.Tier.low, keriBootUrl, null);
        try {
            client.connect();
        } catch (Exception e) {
            client.boot();
            client.connect();
        }
        return client;
    }

    /**
     * Connect to an existing Signify client (without creating a new one)
     */
    public static SignifyClient connectClient(String keriUrl, String keriBootUrl, String bran) throws Exception {
        if (bran == null || bran.isEmpty()) {
            throw new IllegalArgumentException("Passcode (bran) is required");
        }

        SignifyClient client = new SignifyClient(
                keriUrl, bran, Salter.Tier.low, keriBootUrl, null);
        try {
            client.connect();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to connect to KERI client. Make sure the identifier was created with this passcode.", e);
        }
        return client;
    }

    // ------- Identifier Operations ------- //

    /**
     * Create a new AID (Autonomic Identifier)
     */
    public static Aid createAid(SignifyClient client, String name) throws Exception {
        Object id = null;
        String eid = "";
        
        AvailableWitnesses availableWitnesses = getAvailableWitnesses(client);
        List<String> witnessIds = availableWitnesses.witnesses().stream()
            .map(WitnessInfo::eid)
            .toList();
        
        CreateIdentifierArgs kArgs = CreateIdentifierArgs.builder().build();
        kArgs.setToad(availableWitnesses.toad());
        kArgs.setWits(witnessIds);
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

    /**
     * Get an existing AID by name
     */
    public static Aid getExistingAid(SignifyClient client, String name) throws Exception {
        Optional<States.HabState> optionalIdentifier = client.identifiers().get(name);
        if (optionalIdentifier.isEmpty()) {
            return null;
        }

        String id = optionalIdentifier.get().getPrefix();
        Object oobi = client.oobis().get(name, "agent").get();
        String getOobi = ((LinkedHashMap) oobi).get("oobis").toString().replaceAll("[\\[\\]]", "");
        
        return new Aid(name, id, getOobi);
    }

    // ------- Role Operations ------- //

    /**
     * Check if an identifier has a specific end role
     */
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

    /**
     * Get end roles for an identifier
     */
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

    // ------- Witness Operations ------- //

    /**
     * Get available witnesses from the client configuration
     */
    public static AvailableWitnesses getAvailableWitnesses(SignifyClient client) throws Exception {

        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) new Coring.Config(client).get();

        @SuppressWarnings("unchecked")
        List<String> iurls = (List<String>) config.get("iurls");
        if (iurls == null) {
            throw new IllegalStateException(MISCONFIGURED_AGENT_CONFIGURATION);
        }

        Map<String, WitnessInfo> witnessMap = new LinkedHashMap<>();
        for (String oobi : iurls) {
            try {
                java.net.URL url = new java.net.URL(oobi);
                if (url != null) {
                    String[] parts = oobi.split("/oobi/");
                    if (parts.length > 1) {
                        String eid = parts[1].split("/")[0];
                        witnessMap.putIfAbsent(eid, new WitnessInfo(eid, oobi));
                    }
                }
            } catch (Exception e) {
                System.out.println("Error parsing oobi URL: " + oobi + " - " + e.getMessage());
            }
        }

        List<WitnessInfo> uniqueWitnesses = new ArrayList<>(witnessMap.values());
        int size = uniqueWitnesses.size();

        if (size >= 12) return new AvailableWitnesses(8, uniqueWitnesses.subList(0, 12));
        if (size >= 10) return new AvailableWitnesses(7, uniqueWitnesses.subList(0, 10));
        if (size >= 9) return new AvailableWitnesses(6, uniqueWitnesses.subList(0, 9));
        if (size >= 7) return new AvailableWitnesses(5, uniqueWitnesses.subList(0, 7));
        if (size >= 6) return new AvailableWitnesses(4, uniqueWitnesses.subList(0, 6));
        if (size < 6 && size > 0) return new AvailableWitnesses(size, uniqueWitnesses.subList(0, size));

        throw new IllegalStateException(INSUFFICIENT_WITNESSES_AVAILABLE);
    }

    public static void getOrCreateContact(SignifyClient client, String name, String oobi) throws Exception {
        List<Contacting.Contact> list = Arrays.asList(client.contacts().list(null, "alias", "^" + name + "$"));
        if (!list.isEmpty()) {
            Contacting.Contact contact = list.getFirst();
            if (contact.getOobi().equals(oobi)) {
                return;
            }
        }
        Object op = client.oobis().resolve(oobi, name);

        Operation<?> opBody = KeriUtils.waitOperation(client, op);
    }

    public static <T> Operation<T> waitOperation(
            SignifyClient client,
            Object op
    ) throws Exception {
        Operation operation = Operation.fromObject(op);
        operation = client.operations().wait(operation);
        return operation;
    }
}
