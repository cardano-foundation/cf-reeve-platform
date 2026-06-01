package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import java.math.BigDecimal;

/**
 * Projection DTO returned by native aggregation queries in {@link TransactionItemRepository}.
 * Maps one row per account code: the signed sum of all matching transaction-item amounts.
 */
public record AccountCodeTotal(String accountCode, BigDecimal totalAmount) {
}
