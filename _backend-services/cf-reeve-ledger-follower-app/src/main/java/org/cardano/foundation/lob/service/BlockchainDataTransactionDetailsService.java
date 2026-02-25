package org.cardano.foundation.lob.service;

import io.vavr.control.Either;
import org.cardano.foundation.lob.domain.OnChainTxDetails;
import org.springframework.http.ProblemDetail;

import java.util.Optional;

public interface BlockchainDataTransactionDetailsService {

    default Either<ProblemDetail, Optional<OnChainTxDetails>> getTransactionDetails(String transactionHash) {
        return Either.right(Optional.empty());
    }

}
