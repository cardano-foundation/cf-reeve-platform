package org.cardanofoundation.lob.app.blockchain_publisher.service;

import java.io.IOException;
import java.math.BigInteger;
import java.security.DigestException;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import co.nstant.in.cbor.CborException;
import com.bloxbean.cardano.client.crypto.bip39.Sha256Hash;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.util.HexUtil;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.IdentifierConfig;
import org.cardanofoundation.signify.app.aiding.EventResult;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Operation;
import org.cardanofoundation.signify.cesr.exceptions.LibsodiumException;

@Slf4j
@RequiredArgsConstructor
@Service
@ConditionalOnProperty(name = {
    "lob.blockchain-publisher.keri.enabled",
    "lob.blockchain-publisher.enabled"
}, havingValue = "true", matchIfMissing = false)
public class KeriService {


    private final SignifyClient client;
    private final IdentifierConfig identifierConfig;

    public MetadataMap interactWithIdentifier(MetadataMap data) {
        try {
            String dataHash = HexUtil.encodeHexString(Sha256Hash.hash(data.toJson().getBytes()));
            
            EventResult interact = client.identifiers().interact(identifierConfig.getPrefix(), dataHash);
            client.operations().wait(Operation.fromObject(interact.op()));
            Map<String, Object> ked = interact.serder().getKed();
            MetadataMap metadataMap = MetadataBuilder.createMap();
            metadataMap.put("s", ked.get("s").toString());
            metadataMap.put("i", identifierConfig.getPrefix());
            metadataMap.put("d", dataHash);
            metadataMap.put("type", "KERI");
            return metadataMap;

        } catch (DigestException | LibsodiumException | InterruptedException | IOException
                | CborException e) {
            log.error("Failed to interact with identifier", e);
        }
        return null;
    }

}
