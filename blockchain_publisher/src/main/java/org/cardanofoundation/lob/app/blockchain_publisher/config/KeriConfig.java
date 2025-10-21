package org.cardanofoundation.lob.app.blockchain_publisher.config;

import java.util.LinkedHashMap;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.IdentifierConfig;
import org.cardanofoundation.signify.app.aiding.CreateIdentifierArgs;
import org.cardanofoundation.signify.app.aiding.EventResult;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Operation;
import org.cardanofoundation.signify.cesr.Salter;
import org.cardanofoundation.signify.core.States;

@Configuration
@ConditionalOnProperty(name = {
    "lob.blockchain-publisher.keri.enabled",
    "lob.blockchain-publisher.enabled"
}, havingValue = "true", matchIfMissing = false)
@Slf4j
public class KeriConfig {

    @Bean
    public SignifyClient signifyClient(@Value("${lob.blockchain-publisher.keri.url}") String url,
        @Value("${lob.blockchain-publisher.keri.identifier.bran}") String bran,
        @Value("${lob.blockchain-publisher.keri.booturl}") String bootUrl) throws Exception {
    SignifyClient client = new SignifyClient(url, bran, Salter.Tier.low, bootUrl, null);
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
    try {
        States.HabState habState = client.identifiers().get(identifierName);
        prefix = habState.getPrefix();
    } catch (Exception e) {
        EventResult eventResult = client.identifiers().create(identifierName, CreateIdentifierArgs.builder()
            .build());
        client.operations().wait(Operation.fromObject(eventResult.op()));
        prefix = eventResult.serder().getPre();
        EventResult endRole = client.identifiers().addEndRole(identifierName, role, client.getAgent().getPre(), null);
        client.operations().wait(Operation.fromObject(endRole.op()));
    }
    LinkedHashMap<String, Object> response = (LinkedHashMap<String, Object>)client.oobis().get(prefix, role);
    List<String> oobis = (List<String>) response.get("oobis");
    log.info("Created identifier oobis: {}", oobis);
    return IdentifierConfig.builder()
        .prefix(prefix)
        .name(identifierName)
        .role(role)
        .build();
    }
}
