package org.cardano.foundation.lob.service;

import com.bloxbean.cardano.yaci.store.api.blocks.service.BlockService;
import com.bloxbean.cardano.yaci.store.api.transaction.service.TransactionService;
import com.bloxbean.cardano.yaci.store.blocks.domain.Block;
import io.vavr.control.Either;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.cardano.foundation.lob.domain.CardanoNetwork;
import org.cardano.foundation.lob.domain.OnChainTxDetails;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.ProblemDetail;

import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class YaciTransactionDetailsBlockchainDataService implements BlockchainDataTransactionDetailsService {

    private final BlockService blockService;
    private final TransactionService transactionService;

    private final CardanoNetwork network;
    private final FinalityScoreCalculator finalityScoreCalculator;
    private final CacheManager cacheManager;

    @Override
    @Transactional(readOnly = true)
    @Cacheable("trxDetailsCache")
    public Either<ProblemDetail, Optional<OnChainTxDetails>> getTransactionDetails(String transactionHash) {
        return Either.right(transactionService.getTransaction(transactionHash)
                .flatMap(txn -> {
                    return blockService.getBlockByNumber(txn.getBlockHeight()).map(txBlock -> {
                        val blockchainTipSlot = blockService.getLatestBlock()
                                .map(Block::getSlot)
                                .orElse(txBlock.getSlot()); // we fallback to block's hash slot if we can't find the blockchain tip

                        val txAbsoluteSlot = txBlock.getSlot();
                        val slotConfirmations = blockchainTipSlot - txBlock.getSlot();

                        val finalityScore = finalityScoreCalculator.calculateFinalityScore(blockchainTipSlot, txAbsoluteSlot);

                        return OnChainTxDetails.builder()
                                .slotConfirmations(slotConfirmations)
                                .transactionHash(txn.getHash())
                                .finalityScore(finalityScore)
                                .blockHash(txBlock.getHash())
                                .absoluteSlot(txn.getSlot())
                                .network(network)
                                .build();
                    });
                }));
    }

    @Scheduled(fixedRateString = "PT15S")
    public void evictTxDetailsCache() {
        log.debug("Evicting transaction details cache...");
        cacheManager.getCache("trxDetailsCache")
                .clear();
    }

}
