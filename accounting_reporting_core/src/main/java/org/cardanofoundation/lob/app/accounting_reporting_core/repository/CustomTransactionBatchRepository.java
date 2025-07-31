package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import java.util.List;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionBatchEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.BatchSearchRequest;

public interface CustomTransactionBatchRepository {

    List<TransactionBatchEntity> findByFilter(BatchSearchRequest body);

    Long findByFilterCount(BatchSearchRequest body);

}
