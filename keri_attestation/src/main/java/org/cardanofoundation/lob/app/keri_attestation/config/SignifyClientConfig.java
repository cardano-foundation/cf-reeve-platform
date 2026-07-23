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
 * reimplemented locally (no import from blockchain_publisher).
 *
 * <p><b>F1 fix:</b> the constructed {@link SignifyClient} is never itself exposed as a Spring bean —
 * legacy {@code blockchain_publisher} (its {@code KeriConfig}/{@code KeriService}/
 * {@code PublisherHealth}) injects an <em>unqualified</em> {@code SignifyClient}, and now depends on
 * this module; a second unqualified {@code SignifyClient} bean here would make an application context
 * wiring both modules together fail at startup with {@code NoUniqueBeanDefinitionException}, and this
 * module may not touch the legacy files to add a qualifier there. Instead, {@link #keriAttestationClient}
 * is the ONLY bean this class (or this module) exposes for KERIA access — a
 * {@link KeriAttestationClient} holder wrapping the connected {@code SignifyClient}. Every consumer in
 * this module injects {@code KeriAttestationClient} and calls {@link KeriAttestationClient#client()};
 * there is no by-type ambiguity for Spring to resolve, so no {@code @Qualifier} plumbing is needed
 * anywhere in this module.
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

    /**
     * The ONLY bean this class (or this module) exposes for KERIA access — see this class's javadoc
     * (F1 fix) for why the underlying {@link SignifyClient} is never itself a bean.
     */
    @Bean
    public KeriAttestationClient keriAttestationClient(KeriAttestationProperties properties) throws Exception {
        KeriAttestationProperties.Keria keria = properties.keria();
        SignifyClient client = new SignifyClient(keria.url(), keria.bran(), Salter.Tier.low, keria.bootUrl(), null);
        try {
            client.connect();
        } catch (Exception e) {
            client.boot();
            client.connect();
        }
        return new KeriAttestationClient(client);
    }

    @Bean
    public IdentifierRecord keriAttestationAgentIdentifier(KeriAttestationClient keriAttestationClient,
            KeriAttestationProperties properties) throws Exception {
        SignifyClient client = keriAttestationClient.client();
        String identifierName = properties.identifierName();
        String prefix;

        Optional<States.HabState> habState = client.identifiers().get(identifierName);
        if (habState.isPresent()) {
            prefix = habState.get().getPrefix();
        } else {
            log.info("KERI agent identifier {} not found, creating a new one", identifierName);
            prefix = createAid(client, identifierName);
        }
        log.info("Using KERI agent identifier {} with prefix {}", identifierName, prefix);
        return new IdentifierRecord(prefix, identifierName);
    }

    static String createAid(SignifyClient client, String name) throws Exception {
        Object id;
        String eid;

        // Create the agent AID WITH witnesses (from the KERIA agent's own configured witness pool) plus
        // the "agent" end-role added below. The witnesses give the identifier a shared inbound mailbox
        // that any counterparty's KERIA can post to and poll — which is what lets a wallet on a DIFFERENT
        // KERIA deliver an IPEX offer/grant back to this AID so it surfaces in notifications().list().
        // A witness-LESS AID's only inbound endpoint is this backend's own KERIA agent, which a separate
        // wallet-side KERIA deployment cannot necessarily deliver to: observed live 2026-07-23 that a
        // witness-less agent received no grants at all (the wallet reported success, nothing arrived),
        // whereas witnessed agents on this deployment have received them.
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

            // Diagnostic: log the freshly-created AID's actual witness set / toad so a live run can
            // confirm the witnesses were assigned (and see which ones), which is what the wallet's KERIA
            // must be able to reach to deliver a reply back.
            Optional<States.HabState> freshHab = client.identifiers().get(name);
            if (freshHab.isPresent()) {
                States.State freshState = freshHab.get().getState();
                List<String> witnesses = freshState != null ? freshState.getB() : null;
                String toad = freshState != null ? freshState.getBt() : null;
                log.info("created agent AID {}: witnesses={} toad={}", id, witnesses, toad);
            } else {
                log.info("created agent AID {}: no HabState found immediately after creation", id);
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
