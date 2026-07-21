package org.cardanofoundation.lob.app.keri_attestation.config;

import java.net.URI;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.cardanofoundation.signify.app.aiding.CreateIdentifierArgs;
import org.cardanofoundation.signify.app.aiding.EventResult;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Coring;
import org.cardanofoundation.signify.app.coring.Operation;
import org.cardanofoundation.signify.cesr.Salter;
import org.cardanofoundation.signify.core.States;

/**
 * Wires this module's own KERIA {@link SignifyClient} and ensures the platform's KERI agent AID
 * exists at startup. This mirrors blockchain_publisher's {@code KeriConfig} idiom — connect with a
 * boot fallback, get-or-create the identifier, pick witnesses from the agent's own config — but is
 * reimplemented locally (no import from blockchain_publisher) with bean names
 * ({@code keriAttestationSignifyClient}, {@code keriAttestationAgentIdentifier}) chosen so they
 * cannot collide, by name, with legacy {@code KeriConfig}'s {@code signifyClient} /
 * {@code createIdentifier} beans if both modules ever end up registered in the same application
 * context. That only prevents a bean-definition clash, not by-type autowiring ambiguity: this
 * module's own {@code SignifyClient} consumers ({@code KeriAgentService}, {@code KeriOobiService})
 * qualify their injection points with {@code @Qualifier("keriAttestationSignifyClient")} for that
 * reason. blockchain_publisher's own unqualified consumers ({@code KeriService},
 * {@code PublisherHealth}) are outside this module's control — as of this writing no build module in
 * this repo depends on both {@code blockchain_publisher} and {@code keri_attestation} at once, so the
 * two {@code SignifyClient} beans can't yet collide in practice, but whichever future change first
 * wires both modules into one application needs to qualify blockchain_publisher's injection points
 * too.
 *
 * <p>Gated on {@code lob.keri-attestation.keria.url} being configured — deliberately a narrower
 * condition than the module's own {@code lob.keri-attestation.enabled} flag, which some
 * Spring-context tests (e.g. {@code CeremonyRepositoryTest}) turn on without configuring a real
 * KERIA endpoint. Without this extra gate those tests would attempt a live connection during
 * context startup. {@link org.cardanofoundation.lob.app.keri_attestation.service.KeriAgentService}
 * and {@link org.cardanofoundation.lob.app.keri_attestation.service.KeriOobiService} carry the same
 * condition since both require the beans defined here.
 */
@Configuration
@ConditionalOnProperty(prefix = "lob.keri-attestation.keria", name = "url")
@Slf4j
public class SignifyClientConfig {

    public record WitnessInfo(String eid, String oobi) {
    }

    public record AvailableWitnesses(int toad, List<WitnessInfo> witnesses) {
    }

    /** The platform's own KERI agent identity — a slimmed-down analog of blockchain_publisher's
     *  {@code IdentifierConfig}, scoped to just what {@code KeriAgentService} needs. */
    public record IdentifierRecord(String prefix, String name) {
    }

    @Bean
    public SignifyClient keriAttestationSignifyClient(KeriAttestationProperties properties) throws Exception {
        KeriAttestationProperties.Keria keria = properties.keria();
        SignifyClient client = new SignifyClient(keria.url(), keria.bran(), Salter.Tier.low, keria.bootUrl(), null);
        try {
            client.connect();
        } catch (Exception e) {
            client.boot();
            client.connect();
        }
        return client;
    }

    @Bean
    public IdentifierRecord keriAttestationAgentIdentifier(SignifyClient keriAttestationSignifyClient,
            KeriAttestationProperties properties) throws Exception {
        String identifierName = properties.identifierName();
        String prefix;

        Optional<States.HabState> habState = keriAttestationSignifyClient.identifiers().get(identifierName);
        if (habState.isPresent()) {
            prefix = habState.get().getPrefix();
        } else {
            log.info("KERI agent identifier {} not found, creating a new one", identifierName);
            prefix = createAid(keriAttestationSignifyClient, identifierName);
        }
        log.info("Using KERI agent identifier {} with prefix {}", identifierName, prefix);
        return new IdentifierRecord(prefix, identifierName);
    }

    static String createAid(SignifyClient client, String name) throws Exception {
        Object id;
        String eid;

        AvailableWitnesses availableWitnesses = getAvailableWitnesses(client);
        List<String> witnessIds = availableWitnesses.witnesses().stream()
                .map(WitnessInfo::eid)
                .toList();

        CreateIdentifierArgs kArgs = CreateIdentifierArgs.builder().build();
        kArgs.setToad(availableWitnesses.toad());
        kArgs.setWits(witnessIds);

        Optional<States.HabState> optionalIdentifier = client.identifiers().get(name);
        if (optionalIdentifier.isPresent()) {
            id = optionalIdentifier.get().getPrefix();
        } else {
            EventResult result = client.identifiers().create(name, kArgs);
            Operation<Object> op = client.operations().wait(Operation.fromObject(result.op()));
            @SuppressWarnings("unchecked")
            LinkedHashMap<String, Object> resp = (LinkedHashMap<String, Object>) op.getResponse();
            id = resp.get("i");

            if (client.getAgent() != null && client.getAgent().getPre() != null) {
                eid = client.getAgent().getPre();
            } else {
                throw new IllegalStateException("Agent or pre is null");
            }
            if (!hasEndRole(client, name, "agent", eid)) {
                EventResult roleResult = client.identifiers().addEndRole(name, "agent", eid, null);
                client.operations().wait(Operation.fromObject(roleResult.op()));
            }
        }

        return id != null ? id.toString() : null;
    }

    static AvailableWitnesses getAvailableWitnesses(SignifyClient client) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) new Coring.Config(client).get();

        @SuppressWarnings("unchecked")
        List<String> iurls = (List<String>) config.get("iurls");
        if (iurls == null) {
            throw new IllegalStateException("Agent configuration is missing iurls");
        }

        Map<String, WitnessInfo> witnessMap = new LinkedHashMap<>();
        for (String oobi : iurls) {
            try {
                // Parse-only, to skip malformed entries the same way the legacy idiom does.
                new URI(oobi).toURL();
                String[] parts = oobi.split("/oobi/");
                if (parts.length > 1) {
                    String eid = parts[1].split("/")[0];
                    witnessMap.putIfAbsent(eid, new WitnessInfo(eid, oobi));
                }
            } catch (Exception e) {
                log.warn("Error parsing witness OOBI URL {}: {}", oobi, e.getMessage());
            }
        }

        List<WitnessInfo> uniqueWitnesses = new ArrayList<>(witnessMap.values());
        int size = uniqueWitnesses.size();

        if (size >= 12) {
            return new AvailableWitnesses(8, uniqueWitnesses.subList(0, 12));
        }
        if (size >= 10) {
            return new AvailableWitnesses(7, uniqueWitnesses.subList(0, 10));
        }
        if (size >= 9) {
            return new AvailableWitnesses(6, uniqueWitnesses.subList(0, 9));
        }
        if (size >= 7) {
            return new AvailableWitnesses(5, uniqueWitnesses.subList(0, 7));
        }
        if (size >= 6) {
            return new AvailableWitnesses(4, uniqueWitnesses.subList(0, 6));
        }
        if (size > 0) {
            return new AvailableWitnesses(size, uniqueWitnesses.subList(0, size));
        }

        throw new IllegalStateException("Insufficient witnesses available");
    }

    static boolean hasEndRole(SignifyClient client, String alias, String role, String eid) throws Exception {
        List<Map<String, Object>> list = getEndRoles(client, alias, role);
        for (Map<String, Object> endRoleMap : list) {
            String endRole = (String) endRoleMap.get("role");
            String endRoleEid = (String) endRoleMap.get("eid");
            if (endRole != null && endRoleEid != null && endRole.equals(role) && endRoleEid.equals(eid)) {
                return true;
            }
        }
        return false;
    }

    static List<Map<String, Object>> getEndRoles(SignifyClient client, String alias, String role) throws Exception {
        String path = (role != null)
                ? "/identifiers/" + alias + "/endroles/" + role
                : "/identifiers/" + alias + "/endroles";

        HttpResponse<String> response = client.fetch(path, "GET", alias, null);
        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.readValue(response.body(), new TypeReference<>() {
        });
    }
}
