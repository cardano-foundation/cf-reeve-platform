package org.cardanofoundation.lob.app.blockchain_publisher.service;

import java.io.IOException;
import java.security.DigestException;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import co.nstant.in.cbor.CborException;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadataMap;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.IdentifierConfig;
import org.cardanofoundation.signify.app.aiding.EventResult;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Operation;
import org.cardanofoundation.signify.cesr.Diger;
import org.cardanofoundation.signify.cesr.args.RawArgs;
import org.cardanofoundation.signify.cesr.exceptions.LibsodiumException;
import org.cardanofoundation.signify.cesr.util.CoreUtil;

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

    public MetadataMap interactWithIdentifier(CBORMetadataMap data) {
        try {
            byte[] blake3_256 = CoreUtil.blake3_256(
                    CborSerializationUtil.serialize(data.getMap()), 32);
            Diger diger = new Diger(RawArgs.builder().raw(blake3_256).build());

            EventResult interact = client.identifiers().interact(identifierConfig.getPrefix(),
                    diger.getQb64());
            client.operations().wait(Operation.fromObject(interact.op()));
            Map<String, Object> ked = interact.serder().getKed();
            MetadataMap metadataMap = MetadataBuilder.createMap();
            metadataMap.put("s", ked.get("s").toString());
            metadataMap.put("i", identifierConfig.getPrefix());
            metadataMap.put("d", diger.getQb64());
            metadataMap.put("type", "KERI");
            return metadataMap;

        } catch (DigestException | LibsodiumException | InterruptedException | IOException
                | CborException e) {
            log.error("Failed to interact with identifier", e);
        }
        return null;
    }

}
