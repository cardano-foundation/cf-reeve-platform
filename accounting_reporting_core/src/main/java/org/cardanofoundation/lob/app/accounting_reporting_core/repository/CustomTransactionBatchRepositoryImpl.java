package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.RejectionReason.getSourceBasedRejectionReasons;
import static org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.LedgerDispatchStatusView.PENDING;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionBatchEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.BatchSearchRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.LedgerDispatchStatusView;

@RequiredArgsConstructor
@Slf4j
public class CustomTransactionBatchRepositoryImpl implements CustomTransactionBatchRepository {

    private final EntityManager em;

    @Override
    public List<TransactionBatchEntity> findByFilter(BatchSearchRequest body) {
        val builder = em.getCriteriaBuilder();
        CriteriaQuery<TransactionBatchEntity> criteriaQuery = builder.createQuery(TransactionBatchEntity.class);
        Root<TransactionBatchEntity> rootEntry = criteriaQuery.from(TransactionBatchEntity.class);

        val andPredicates = queryCriteria(rootEntry, builder, body);

        criteriaQuery.select(rootEntry);
        criteriaQuery.where(andPredicates.toArray(new Predicate[0]));
        criteriaQuery.orderBy(builder.desc(rootEntry.get("createdAt")));
        // Without this line the query only returns one row.
        criteriaQuery.groupBy(rootEntry.get("id"));

        TypedQuery<TransactionBatchEntity> theQuery = em.createQuery(criteriaQuery);
        theQuery.setMaxResults(body.getLimit());

        if (null != body.getPage() && 0 < body.getPage()) {
            theQuery.setFirstResult(body.getPage() * body.getLimit());
        }

        return theQuery.getResultList();
    }

    @Override
    public Long findByFilterCount(BatchSearchRequest body) {
        val builder = em.getCriteriaBuilder();
        CriteriaQuery<Long> criteriaQuery = builder.createQuery(Long.class);
        Root<TransactionBatchEntity> rootEntry = criteriaQuery.from(TransactionBatchEntity.class);
        Collection<Predicate> andPredicates = queryCriteria(rootEntry, builder, body);

        criteriaQuery.select(builder.count(rootEntry));
        criteriaQuery.where(andPredicates.toArray(new Predicate[0]));
        criteriaQuery.orderBy(builder.desc(rootEntry.get("createdAt")));
        // Without this line the query only returns one row.
        criteriaQuery.groupBy(rootEntry.get("id"));

        TypedQuery<Long> theQuery = em.createQuery(criteriaQuery);

        return (long) theQuery.getResultList().size();
    }

    private Collection<Predicate> queryCriteria(Root<TransactionBatchEntity> rootEntry, CriteriaBuilder builder, BatchSearchRequest body) {
        Collection<Predicate> andPredicates = new ArrayList<>();

        andPredicates.add(builder.equal(rootEntry.get("filteringParameters").get("organisationId"), body.getOrganisationId()));

        if (!body.getBatchStatistics().isEmpty()) {
            Join<TransactionBatchEntity, TransactionEntity> transactionEntityJoin = rootEntry.join("transactions", JoinType.INNER);
            List<Predicate> orPredicates = new ArrayList<>();

            if (body.getBatchStatistics().stream().anyMatch(s -> s.equals(LedgerDispatchStatusView.INVALID))) {
                Join<TransactionEntity, TransactionItemEntity> transactionItemJoin = transactionEntityJoin.join("items", JoinType.INNER);

                orPredicates.add(transactionItemJoin.get("rejection").get("rejectionReason").in(getSourceBasedRejectionReasons(Source.ERP).stream().toList()));
                Subquery<String> subqueryErp = builder.createQuery().subquery(String.class);
                Root<TransactionEntity> transactionEntityRoot = subqueryErp.from(TransactionEntity.class);
                subqueryErp.select(transactionEntityRoot.get("id"));
                Predicate whereErp = builder.and(
                        builder.equal(transactionEntityRoot.get("violations").get("source"), Source.ERP),
                        builder.equal(transactionEntityRoot.get("id"), transactionEntityJoin.get("id"))
                );
                subqueryErp.where(whereErp);
                orPredicates.add((builder.in(transactionEntityJoin.get("id")).value(subqueryErp)));
            }

            if (body.getBatchStatistics().stream().anyMatch(s -> s.equals(PENDING))) {
                Subquery<String> subqueryItemsIn = builder.createQuery().subquery(String.class);
                Root<TransactionItemEntity> transactionEntityRootItem = subqueryItemsIn.from(TransactionItemEntity.class);

                subqueryItemsIn.select(transactionEntityRootItem.get("transaction").get("id"));
                Predicate whereItem =
                        builder.and(
                                transactionEntityRootItem.get("rejection").get("rejectionReason").in(getSourceBasedRejectionReasons(Source.LOB).stream().toList()),
                                builder.equal(transactionEntityRootItem.get("transaction").get("id"), transactionEntityJoin.get("id"))
                        );
                subqueryItemsIn.where(whereItem);

                Subquery<String> subqueryItemsOut = builder.createQuery().subquery(String.class);
                Root<TransactionItemEntity> transactionEntityRootItem2 = subqueryItemsOut.from(TransactionItemEntity.class);
                subqueryItemsOut.select(transactionEntityRootItem2.get("transaction").get("id"));
                Predicate whereItem2 =
                        builder.and(
                                transactionEntityRootItem2.get("rejection").get("rejectionReason").in(getSourceBasedRejectionReasons(Source.ERP).stream().toList()),
                                builder.equal(transactionEntityRootItem2.get("transaction").get("id"), transactionEntityJoin.get("id"))
                        );
                subqueryItemsOut.where(whereItem2);

                Subquery<String> subqueryErp = builder.createQuery(transactionEntityJoin.getClass()).subquery(String.class);
                Root<TransactionEntity> transactionEntityRoot = subqueryErp.from(TransactionEntity.class);
                subqueryErp.select(transactionEntityRoot.get("id"));
                Predicate whereErp = builder.and(
                        builder.equal(transactionEntityRoot.get("violations").get("source"), Source.ERP),
                        builder.equal(transactionEntityRoot.get("id"), transactionEntityJoin.get("id"))
                );
                subqueryErp.where(whereErp);

                Subquery<String> subqueryLob = builder.createQuery(transactionEntityJoin.getClass()).subquery(String.class);
                Root<TransactionEntity> transactionEntityRootLob = subqueryLob.from(TransactionEntity.class);
                subqueryLob.select(transactionEntityRootLob.get("id"));
                Predicate whereLob = builder.and(
                        builder.equal(transactionEntityRootLob.get("violations").get("source"), Source.LOB),
                        builder.equal(transactionEntityRootLob.get("id"), transactionEntityJoin.get("id"))
                );
                subqueryLob.where(whereLob);

                orPredicates.add(builder.and(
                        builder.in(transactionEntityJoin.get("id")).value(subqueryItemsOut).not(),
                        builder.in(transactionEntityJoin.get("id")).value(subqueryErp).not(),
                        builder.or(
                                builder.in(transactionEntityJoin.get("id")).value(subqueryItemsIn),
                                builder.in(transactionEntityJoin.get("id")).value(subqueryLob),
                                builder.equal(transactionEntityJoin.get("automatedValidationStatus"), TxValidationStatus.FAILED)
                        )
                ));
            }

            if (body.getBatchStatistics().stream().anyMatch(s -> s.equals(LedgerDispatchStatusView.APPROVE))) {

                Subquery<String> subqueryErp = builder.createQuery().subquery(String.class);
                Root<TransactionEntity> transactionEntityRoot = subqueryErp.from(TransactionEntity.class);
                subqueryErp.select(transactionEntityRoot.get("id"));
                Predicate whereErp = builder.and(
                        builder.or(
                                builder.equal(transactionEntityRoot.get("violations").get("source"), Source.ERP),
                                builder.equal(transactionEntityRoot.get("violations").get("source"), Source.LOB)
                        ),
                        builder.equal(transactionEntityRoot.get("id"), transactionEntityJoin.get("id"))
                );
                subqueryErp.where(whereErp);

                Subquery<String> subqueryReject = builder.createQuery(transactionEntityJoin.getClass()).subquery(String.class);
                Root<TransactionItemEntity> transactionItemEntityRoot = subqueryReject.from(TransactionItemEntity.class);
                subqueryReject.select(transactionItemEntityRoot.get("transaction").get("id"));
                Predicate whereReject = builder.and(
                        builder.isNotNull(transactionItemEntityRoot.get("rejection").get("rejectionReason")),
                        builder.equal(transactionItemEntityRoot.get("transaction").get("id"), transactionEntityJoin.get("id"))
                );
                subqueryReject.where(whereReject);

                orPredicates.add(builder.and(
                                builder.equal(transactionEntityJoin.get("transactionApproved"), false),
                                builder.equal(transactionEntityJoin.get("ledgerDispatchApproved"), false),
                                builder.equal(transactionEntityJoin.get("automatedValidationStatus"), TxValidationStatus.VALIDATED),
                                builder.in(transactionEntityJoin.get("id")).value(subqueryReject).not(),
                                builder.in(transactionEntityJoin.get("id")).value(subqueryErp).not()
                        )
                );
            }

            if (body.getBatchStatistics().stream().anyMatch(s -> s.equals(LedgerDispatchStatusView.PUBLISH))) {
                Subquery<String> subqueryErp = builder.createQuery().subquery(String.class);
                Root<TransactionEntity> transactionEntityRoot = subqueryErp.from(TransactionEntity.class);
                subqueryErp.select(transactionEntityRoot.get("id"));
                Predicate whereErp = builder.and(
                        builder.or(
                                builder.equal(transactionEntityRoot.get("violations").get("source"), Source.ERP),
                                builder.equal(transactionEntityRoot.get("violations").get("source"), Source.LOB)
                        ),
                        builder.equal(transactionEntityRoot.get("id"), transactionEntityJoin.get("id"))
                );
                subqueryErp.where(whereErp);

                Subquery<String> subqueryReject = builder.createQuery(transactionEntityJoin.getClass()).subquery(String.class);
                Root<TransactionItemEntity> transactionItemEntityRoot = subqueryReject.from(TransactionItemEntity.class);
                subqueryReject.select(transactionItemEntityRoot.get("transaction").get("id"));
                Predicate whereReject = builder.and(
                        builder.isNotNull(transactionItemEntityRoot.get("rejection").get("rejectionReason")),
                        builder.equal(transactionItemEntityRoot.get("transaction").get("id"), transactionEntityJoin.get("id"))
                );
                subqueryReject.where(whereReject);

                orPredicates.add(builder.and(
                                builder.equal(transactionEntityJoin.get("transactionApproved"), true),
                                builder.equal(transactionEntityJoin.get("ledgerDispatchApproved"), false),
                                builder.equal(transactionEntityJoin.get("automatedValidationStatus"), TxValidationStatus.VALIDATED),
                                builder.in(transactionEntityJoin.get("id")).value(subqueryReject).not(),
                                builder.in(transactionEntityJoin.get("id")).value(subqueryErp).not()
                        )
                );
            }

            if (body.getBatchStatistics().stream().anyMatch(s -> s.equals(LedgerDispatchStatusView.PUBLISHED))) {
                Subquery<String> subqueryErp = builder.createQuery().subquery(String.class);
                Root<TransactionEntity> transactionEntityRoot = subqueryErp.from(TransactionEntity.class);
                subqueryErp.select(transactionEntityRoot.get("id"));
                Predicate whereErp = builder.and(
                        builder.or(
                                builder.equal(transactionEntityRoot.get("violations").get("source"), Source.ERP),
                                builder.equal(transactionEntityRoot.get("violations").get("source"), Source.LOB)
                        ),
                        builder.equal(transactionEntityRoot.get("id"), transactionEntityJoin.get("id"))
                );
                subqueryErp.where(whereErp);

                Subquery<String> subqueryReject = builder.createQuery(transactionEntityJoin.getClass()).subquery(String.class);
                Root<TransactionItemEntity> transactionItemEntityRoot = subqueryReject.from(TransactionItemEntity.class);
                subqueryReject.select(transactionItemEntityRoot.get("transaction").get("id"));
                Predicate whereReject = builder.and(
                        builder.isNotNull(transactionItemEntityRoot.get("rejection").get("rejectionReason")),
                        builder.equal(transactionItemEntityRoot.get("transaction").get("id"), transactionEntityJoin.get("id"))
                );
                subqueryReject.where(whereReject);

                orPredicates.add(builder.and(
                                builder.equal(transactionEntityJoin.get("transactionApproved"), true),
                                builder.equal(transactionEntityJoin.get("ledgerDispatchApproved"), true),
                                builder.equal(transactionEntityJoin.get("automatedValidationStatus"), TxValidationStatus.VALIDATED),
                                builder.in(transactionEntityJoin.get("id")).value(subqueryReject).not(),
                                builder.in(transactionEntityJoin.get("id")).value(subqueryErp).not()
                        )
                );
            }

            andPredicates.add(builder.or(orPredicates.toArray(new Predicate[0])));
        }

        if (!body.getTransactionTypes().isEmpty()) {
            Expression<?> bitwiseAnd = builder.function("BITAND", Integer.class, rootEntry.get("filteringParameters").get("transactionTypes"), builder.literal(body.getTransactionTypes().stream().toList()));
            andPredicates.add(builder.notEqual(bitwiseAnd, 0));
        }

        if (null != body.getFrom()) {
            LocalDateTime localDateTime1 = body.getFrom().atStartOfDay();
            andPredicates.add(builder.greaterThanOrEqualTo(rootEntry.get("createdAt"), localDateTime1));
        }

        if (null != body.getTo()) {
            val localDateTime2 = body.getTo().atTime(23, 59, 59);

            andPredicates.add(builder.lessThanOrEqualTo(rootEntry.get("createdAt"), localDateTime2));
        }

        if (!body.getTxStatus().isEmpty()) {
            Join<TransactionBatchEntity, TransactionEntity> transactionEntityJoin = rootEntry.join("transactions", JoinType.INNER);
            andPredicates.add(builder.in(transactionEntityJoin.get("status")).value(body.getTxStatus()));
        }

        return andPredicates;
    }

}
