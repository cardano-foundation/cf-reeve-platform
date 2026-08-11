package org.cardanofoundation.lob.app.blockchain_publisher.config;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.IdentifierConfig;
import org.cardanofoundation.signify.app.aiding.CreateIdentifierArgs;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Coring;
import org.cardanofoundation.signify.generated.keria.model.AgentConfig;
import org.cardanofoundation.signify.generated.keria.model.HabState;
import org.cardanofoundation.signify.generated.keria.model.Tier;

@Configuration
@ConditionalOnProperty(name = "lob.blockchain-publisher.keri.enabled", havingValue = "true", matchIfMissing = false)
@Slf4j
public class KeriConfig {

    public record WitnessInfo(String eid, String oobi) {}
    public record AvailableWitnesses(int toad, List<WitnessInfo> witnesses) {}

    @Bean
    public SignifyClient signifyClient(@Value("${lob.blockchain-publisher.keri.url}") String url,
        @Value("${lob.blockchain-publisher.keri.identifier.bran}") String bran,
        @Value("${lob.blockchain-publisher.keri.booturl}") String bootUrl) throws Exception {
    SignifyClient client = new SignifyClient(url, bran, Tier.LOW, bootUrl, null);
    try {
        client.connect();
    } catch (Exception e) {
        client.boot();
        client.connect();
    }
    return client;
    }

    @Bean
    public IdentifierConfig createIdentifier(
        @Value("${lob.blockchain-publisher.keri.identifier.name}") String identifierName,
        @Value("${lob.blockchain-publisher.keri.identifier.role}") String role, SignifyClient client) throws Exception {
    String prefix;

    Optional<HabState> habState = client.identifiers().get(identifierName);
    if (habState.isPresent()) {
        prefix = habState.get().getPrefix();
    } else {
        log.info("KERI Identifier with name {} not found, creating new one", identifierName);
        prefix = createAid(client, identifierName);
    }
    log.info("Using KERI Identifier with name {} and prefix {}", identifierName, prefix);
    return IdentifierConfig.builder()
        .prefix(prefix)
        .name(identifierName)
        .role(role)
        .build();
    }

    public static String createAid(SignifyClient client, String name) throws Exception {
        Object id = null;
        String eid = "";

        AvailableWitnesses availableWitnesses = getAvailableWitnesses(client);
        List<String> witnessIds = availableWitnesses.witnesses().stream()
                .map(WitnessInfo::eid)
                .toList();

        CreateIdentifierArgs kArgs = CreateIdentifierArgs.builder().build();
        kArgs.setToad(availableWitnesses.toad());
        kArgs.setWits(witnessIds);
        Optional<HabState> optionalIdentifier = client.identifiers().get(name);
        if (optionalIdentifier.isPresent()) {
            id = optionalIdentifier.get().getPrefix();
        } else {
            client.operations().wait(client.identifiers().create(name, kArgs).op());
            // Read the prefix back off the identifier rather than out of the operation: operations are
            // marker interfaces now and carry no response body.
            id = client.identifiers().get(name)
                    .orElseThrow(() -> new IllegalStateException(
                            "Agent AID %s was created but cannot be read back.".formatted(name)))
                    .getPrefix();
            if (client.getAgent() != null && client.getAgent().getPre() != null) {
                eid = client.getAgent().getPre();
            } else {
                throw new IllegalStateException("Agent or pre is null");
            }
            if (!hasEndRole(client, name, "agent", eid)) {
                client.operations().wait(client.identifiers().addEndRole(name, "agent", eid, null).op());
            }
        }

        // Typed OOBI now, not a map — the old cast threw ClassCastException at startup.
        String getOobi = String.join(",", client.oobis().get(name, "agent").get().getOobis());
        log.info("Created new KERI Identifier with prefix {} and OOBI {}", id != null ? id.toString() : null, getOobi);
        return id != null ? id.toString() : null;
    }

    public static AvailableWitnesses getAvailableWitnesses(SignifyClient client) throws Exception {
        // Typed AgentConfig now, not a map — the old cast threw ClassCastException the first time an
        // agent AID had to be created (i.e. on a fresh KERIA account), taking startup down with it.
        AgentConfig config = new Coring.Config(client).get();

        List<String> iurls = config.getIurls();
        if (iurls == null) {
            throw new IllegalStateException("Agent configuration is missing iurls");
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

        throw new IllegalStateException("Insufficient witnesses available");
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
}
