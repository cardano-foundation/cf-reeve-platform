package org.cardano.foundation.lob.service;

import com.bloxbean.cardano.yaci.store.api.blocks.service.BlockService;
import com.bloxbean.cardano.yaci.store.core.service.EraService;
import io.vavr.control.Either;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardano.foundation.lob.domain.CardanoNetwork;
import org.cardano.foundation.lob.domain.ChainTip;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.zalando.problem.Problem;

import static org.zalando.problem.Status.INTERNAL_SERVER_ERROR;

import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class YaciChainTipService implements BlockchainDataChainTipService {

    private final BlockService blockService;
    private final ChainSyncService chainSyncService;
    private final EraService eraService;

    private final CardanoNetwork network;
    private final CacheManager cacheManager;

    @Override
    @Transactional(readOnly = true)
    @Cacheable("chainTipCache")
    public Either<Problem, ChainTip> getChainTip() {
        var latestBlockM = blockService.getLatestBlock();

        if (latestBlockM.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle("CHAIN_TIP_NOT_FOUND")
                    .withDetail("Unable to get chain tip from backend service.")
                    .withStatus(INTERNAL_SERVER_ERROR)
                    .build()
            );
        }
        var latestBlock = latestBlockM.orElseThrow();

        var chainSync = chainSyncService.getSyncStatus(true);

        Optional<Integer> currentEpoch = eraService.getTipAndCurrentEpoch().isPresent() ? Optional.of(eraService.getTipAndCurrentEpoch().get()._2) : Optional.empty();

        return Either.right(ChainTip.builder()
                .blockHash(latestBlock.getHash())
                .epochNo(currentEpoch)
                .absoluteSlot(latestBlock.getSlot())
                .network(network)
                .isSynced(chainSync.isSynced())
                .build());
    }

    @Scheduled(fixedRateString = "PT15S")
    public void evictChainTipCache() {
        log.debug("Evicting chain tip cache...");
        cacheManager.getCache("chainTipCache")
                .clear();
    }

}
