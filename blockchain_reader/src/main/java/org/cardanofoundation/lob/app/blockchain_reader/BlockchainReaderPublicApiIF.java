package org.cardanofoundation.lob.app.blockchain_reader;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.val;

import io.vavr.control.Either;
import org.springframework.http.ProblemDetail;

import org.cardanofoundation.lob.app.blockchain_common.domain.CardanoNetwork;
import org.cardanofoundation.lob.app.blockchain_common.domain.ChainTip;
import org.cardanofoundation.lob.app.blockchain_common.domain.FinalityScore;
import org.cardanofoundation.lob.app.blockchain_common.domain.OnChainTxDetails;

public interface BlockchainReaderPublicApiIF {

    Either<ProblemDetail, ChainTip> getChainTip();

    Either<ProblemDetail, Optional<OnChainTxDetails>> getTxDetails(String transactionHash);

    Either<ProblemDetail, Map<String, Boolean>> isOnChain(Set<String> transactionIds);

    @RequiredArgsConstructor
    class Noop implements BlockchainReaderPublicApiIF {

        private final CardanoNetwork network;

        @Override
        public Either<ProblemDetail, ChainTip> getChainTip() {
            return Either.right(ChainTip.builder()
                    .absoluteSlot(1)
                    .blockHash("0db89ee763a3f9fdb3f5b6e0e0d21b2da8d5768034d9090ac2d011b94bf0f9ef")
                    .epochNo(Optional.of(1))
                    .network(network)
                    .isSynced(true)
                    .build()
            );
        }

        @Override
        public Either<ProblemDetail, Optional<OnChainTxDetails>> getTxDetails(String transactionHash) {
            return Either.right(Optional.of(OnChainTxDetails.builder()
                    .transactionHash(transactionHash)
                    .blockHash("0db89ee763a3f9fdb3f5b6e0e0d21b2da8d5768034d9090ac2d011b94bf0f9ef")
                    .absoluteSlot(1)
                    .slotConfirmations(1_000_000)
                    .network(network)
                    .finalityScore(FinalityScore.FINAL)
                    .build())
            );
        }

        @Override
        public Either<ProblemDetail, Map<String, Boolean>> isOnChain(Set<String> transactionIds) {
            val result = transactionIds.stream()
                    .collect(Collectors.toMap(
                            transactionId -> transactionId,
                            transactionId -> true
                    ));

            return Either.right(result);
        }

    }

}
