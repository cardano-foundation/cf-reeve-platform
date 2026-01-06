package org.cardanofoundation.lob.app.blockchain_publisher.service.transation_submit;

import com.bloxbean.cardano.client.api.exception.ApiException;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.L1Submission;

public interface TransactionSubmissionService {

    /**
     * Submit transaction and return transaction  hash.
     *
     * @param txData
     * @return transaction hash
     */
    String submitTransaction(byte[] txData);

    /**
     * Submits transaction and gets L1 confirmation data
     * @param txData
     * @return
     */
    L1Submission submitTransactionWithPossibleConfirmation(byte[] txData, String receiverAddress) throws ApiException;

}
