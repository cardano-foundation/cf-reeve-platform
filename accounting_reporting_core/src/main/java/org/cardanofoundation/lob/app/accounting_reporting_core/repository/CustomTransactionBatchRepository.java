package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import java.util.List;

import org.springframework.data.domain.Sort;

import io.vavr.control.Either;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionBatchEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.BatchSearchRequest;

public interface CustomTransactionBatchRepository {

    Either<Problem, List<TransactionBatchEntity>> findByFilter(BatchSearchRequest body, Sort sort);

    Long findByFilterCount(BatchSearchRequest body);

}
