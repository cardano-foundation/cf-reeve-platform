package org.cardano.foundation.lob.service;

import com.bloxbean.cardano.client.metadata.cbor.CBORMetadataList;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadataMap;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.cardano.foundation.lob.domain.LOBOnChainBatch;
import org.cardano.foundation.lob.domain.LOBOnChainTransaction;
import org.cardano.foundation.lob.domain.view.IpfsFile;
import org.cardano.foundation.lob.domain.view.IpfsTransaction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class MetadataDeserialiser {

    @Value("${ipfs.gateway:https://ipfs.io/ipfs/}")
    private String ipfsGateway;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper;

    public Optional<LOBOnChainBatch> decode(CBORMetadataMap payload) {
        val maybeOrg = Optional.ofNullable(payload.get("org"));
        if (maybeOrg.isEmpty()) {
            log.warn("\"org\" is missing. Skipping.");
            return Optional.empty();
        }
        if (!(maybeOrg.get() instanceof CBORMetadataMap)) {
            log.warn("\"org\" is not an object. Skipping.");
            return Optional.empty();
        }
        val org = (CBORMetadataMap) maybeOrg.get();

        val maybeOrgId = Optional.ofNullable(org.get("id"));
        if (maybeOrgId.isEmpty()) {
            log.warn("\"orgId\" is missing in \"org\". Skipping.");
            return Optional.empty();
        }
        if (!(maybeOrgId.get() instanceof String)) {
            log.warn("\"orgId\" in \"org\" is not of type String. Skipping.");
            return Optional.empty();
        }
        val orgId = (String) maybeOrgId.get();

        Set<LOBOnChainTransaction> txs = new LinkedHashSet<>();
        if(payload.get("type").equals("INDIVIDUAL_TRANSACTIONS")) {
            if(payload.get("ipfs") != null) {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(ipfsGateway + payload.get("ipfs")))
                        .GET()
                        .build();
                try {
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    IpfsFile dataIpfs = objectMapper.readValue(response.body(), IpfsFile.class);
                    txs.addAll(dataIpfs.data().stream().map(tx -> LOBOnChainTransaction.builder().id(tx.id()).build()).collect(Collectors.toSet()));
                } catch (IOException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            } else {
                txs = readTransactions((CBORMetadataList) payload.get("data"));
            }
        }
        return Optional.of(LOBOnChainBatch.builder()
                .organisationId(orgId)
                .transactions(txs)
                .build());
    }

    private LOBOnChainTransaction readTransaction(CBORMetadataMap cborMetadataMap) {
        return LOBOnChainTransaction.builder()
                .id((String) cborMetadataMap.get("id"))
                .build();
    }

    private Set<LOBOnChainTransaction> readTransactions(CBORMetadataList cborMetadataList) {
        val txs = new LinkedHashSet<LOBOnChainTransaction>();

        for (int i = 0; i < cborMetadataList.size(); i++) {
            val lobTx = readTransaction((CBORMetadataMap) cborMetadataList.getValueAt(i));

            txs.add(lobTx);
        }

        return txs;
    }

}
