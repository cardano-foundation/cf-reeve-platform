package org.cardano.foundation.lob.service;

import com.bloxbean.cardano.client.metadata.cbor.CBORMetadata;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadataMap;
import com.bloxbean.cardano.yaci.store.metadata.domain.TxMetadataEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.cardano.foundation.lob.domain.entity.TransactionEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.Optional;

import static com.bloxbean.cardano.client.util.HexUtil.decodeHexString;

@Service
@Slf4j
@RequiredArgsConstructor
public class LOBOnChainBatchProcessor {

    private final TransactionService transactionService;
    private final MetadataDeserialiser metadataDeserialiser;

    @Value("${lob.transaction.metadata_label:1447}")
    private int metadataLabel;

    @EventListener
    public void metadataEvent(TxMetadataEvent event) {
        val txMetadataList = event.getTxMetadataList();
        for (val txEvent : txMetadataList) {
            if (txEvent.getLabel().equalsIgnoreCase(String.valueOf(metadataLabel))) {
                val cborBytes = decodeHexString(txEvent.getCbor().replace("\\x", ""));
                val cborMetadata = CBORMetadata.deserialize(cborBytes);

                val envelopeCborMap = Optional.ofNullable((CBORMetadataMap) cborMetadata.get(BigInteger.valueOf(metadataLabel)))
                        .orElseThrow();

                val lobBatch = metadataDeserialiser.decode(envelopeCborMap);

                if (lobBatch.isEmpty()) {
                    log.warn("Failed to decode transaction {}. Block: {}.", txEvent.getTxHash(), event.getEventMetadata());
                    continue;
                }

                for (val lobTx : lobBatch.get().getTransactions()) {
                    val tx = new TransactionEntity();
                    tx.setId(lobTx.getId());
                    tx.setOrganisationId(lobBatch.get().getOrganisationId());
                    tx.setL1TransactionHash(txEvent.getTxHash());
                    tx.setL1AbsoluteSlot(txEvent.getSlot());

                    transactionService.storeIfNew(tx);
                }
            }
        }
    }

}
