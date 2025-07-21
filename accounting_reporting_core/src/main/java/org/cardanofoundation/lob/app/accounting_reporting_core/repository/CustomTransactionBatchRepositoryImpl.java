package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

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

import org.springframework.data.domain.Sort;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionBatchEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.BatchSearchRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.LedgerDispatchStatusView;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.sort.TransactionFieldSortRequest;

@RequiredArgsConstructor
@Slf4j
public class CustomTransactionBatchRepositoryImpl implements CustomTransactionBatchRepository {

    private final EntityManager em;

    @Override
    public List<TransactionBatchEntity> findByFilter(BatchSearchRequest body, Sort sort) {
        val builder = em.getCriteriaBuilder();
        CriteriaQuery<TransactionBatchEntity> criteriaQuery = builder.createQuery(TransactionBatchEntity.class);
        Root<TransactionBatchEntity> rootEntry = criteriaQuery.from(TransactionBatchEntity.class);

        val andPredicates = queryCriteria(rootEntry, builder, body);

        criteriaQuery.select(rootEntry);
        criteriaQuery.where(andPredicates.toArray(new Predicate[0]));
        criteriaQuery.orderBy(builder.desc(rootEntry.get("createdAt")));
        List<Order> jpaOrders = new ArrayList<>();

        if (sort.isSorted()) {
            sort.get().forEach(consumer -> {
                if (consumer.isAscending()) {
                    jpaOrders.add(builder.asc(getPath(rootEntry, TransactionFieldSortRequest.valueOf(consumer.getProperty()).getCode())));
                }
                if (consumer.isDescending()) {
                    jpaOrders.add(builder.desc(getPath(rootEntry, TransactionFieldSortRequest.valueOf(consumer.getProperty()).getCode())));
                }
            });
            criteriaQuery.orderBy(jpaOrders);
        }

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
            List<Predicate> orPredicates = new ArrayList<>();

            if (body.getBatchStatistics().stream().anyMatch(s -> s.equals(LedgerDispatchStatusView.INVALID))) {
                orPredicates.add(builder.ge(rootEntry.get("batchStatistics").get("invalidTransactions"), 1));
            }

            if (body.getBatchStatistics().stream().anyMatch(s -> s.equals(PENDING))) {
                orPredicates.add(builder.ge(rootEntry.get("batchStatistics").get("pendingTransactions"), 1));
            }

            if (body.getBatchStatistics().stream().anyMatch(s -> s.equals(LedgerDispatchStatusView.APPROVE))) {
                orPredicates.add(builder.ge(rootEntry.get("batchStatistics").get("readyToApproveTransactions"), 1));
            }

            if (body.getBatchStatistics().stream().anyMatch(s -> s.equals(LedgerDispatchStatusView.PUBLISH))) {
                orPredicates.add(builder.ge(rootEntry.get("batchStatistics").get("approvedTransactions"), 1));
            }

            if (body.getBatchStatistics().stream().anyMatch(s -> s.equals(LedgerDispatchStatusView.PUBLISHED))) {
                orPredicates.add(builder.ge(rootEntry.get("batchStatistics").get("publishedTransactions"), 1));
            }
            andPredicates.add(builder.or(orPredicates.toArray(new Predicate[0])));
        }

        if (!body.getTransactionTypes().isEmpty()) {
            Expression<?> bitwiseAnd = builder.function("BITAND", Integer.class, rootEntry.get("filteringParameters").get("transactionTypes"), builder.literal(body.getTransactionTypes().stream().toList()));
            andPredicates.add(builder.notEqual(bitwiseAnd, 0));
        }

        if (body.getCreatedBy() != null && !body.getCreatedBy().isEmpty()) {
            andPredicates.add(builder.equal(rootEntry.get("createdBy"), body.getCreatedBy()));
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
            andPredicates.add(builder.in(transactionEntityJoin.get("overallStatus")).value(body.getTxStatus()));
        }

        if (body.getBatchId() != null && !body.getBatchId().isEmpty()) {
            andPredicates.add(builder.and(builder.equal(rootEntry.get("id"), body.getBatchId())));
            // if the batchId is set then we search only for batchId and organisationId
            //andPredicates = Collections.singleton(builder.and(builder.equal(rootEntry.get("id"), body.getBatchId()), builder.equal(rootEntry.get("filteringParameters").get("organisationId"), body.getOrganisationId())));
        }

        return andPredicates;
    }

    // Helper method to get a Path for a dot-separated property
    private <T> Path<T> getPath(Root<?> root, String propertyPath) {
        String[] pathParts = propertyPath.split("\\.");
        Path<?> path = root;
        for (String part : pathParts) {
            path = path.get(part);
        }
        return (Path<T>) path;
    }
}
