package org.cardanofoundation.lob.app.blockchain_publisher.service.transation_submit;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.model.TransactionContent;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.L1Submission;

@Slf4j
@RequiredArgsConstructor
public class DefaultTransactionSubmissionService implements TransactionSubmissionService {

    private final BlockchainTransactionSubmissionService transactionSubmissionService;

    private final BackendService backendService;
    private final UtxoSupplier utxoSupplier;

    private final Clock clock;

    private final int sleepTimeSeconds;

    private final int timeoutInSeconds;

    @Override
    public String submitTransaction(byte[] txData) {
        log.info("Submitting transaction without confirmation.., txId:{}", TransactionUtil.getTxHash(txData));

        return transactionSubmissionService.submitTransaction(txData);
    }

    @Override
    public L1Submission submitTransactionWithPossibleConfirmation(byte[] txData, String receiverAddress) throws ApiException {
        log.info("Submitting transaction with confirmation.., txId:{}", TransactionUtil.getTxHash(txData));
        String txHash = submitTransaction(txData);

        LocalDateTime start = LocalDateTime.now(clock);
        LocalDateTime future = start.plusSeconds(timeoutInSeconds);

        while (LocalDateTime.now(clock).isBefore(future)) {
            Result<TransactionContent> transactionDetailsR = backendService.getTransactionService().getTransaction(txHash);

            if (!transactionDetailsR.isSuccessful()) {
                log.warn("Transaction not found on chain yet. Sleeping for {} seconds... until deadline:{}", sleepTimeSeconds, future);
                try {
                    Thread.sleep(sleepTimeSeconds * 1000L);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                continue;
            }

            TransactionContent transactionContent = transactionDetailsR.getValue();
            Long absoluteSlot = transactionContent.getSlot();
            checkIfUtxoAvailable(txHash, receiverAddress);

            return new L1Submission(txHash, Optional.of(absoluteSlot), true);
        }

        return new L1Submission(txHash, Optional.empty(), false);
    }

    protected void checkIfUtxoAvailable(String txHash, String address) {
        Optional<Utxo> utxo = Optional.empty();
        int count = 0;

        while (utxo.isEmpty()) {
            if (count++ >= 50)
                break;

            List<Utxo> utxos = utxoSupplier.getAll(address);

            utxo = utxos
                    .stream()
                    .filter(u -> u.getTxHash().equals(txHash))
                    .findFirst();

            log.info("Try to get new output... txhash: " + txHash);

            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {}
        }
    }

}
