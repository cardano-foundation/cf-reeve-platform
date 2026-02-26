package org.cardano.foundation.lob.service;

import io.vavr.control.Either;
import org.cardano.foundation.lob.domain.ChainTip;
import org.springframework.http.ProblemDetail;

public interface BlockchainDataChainTipService {

    Either<ProblemDetail, ChainTip> getChainTip();

}
