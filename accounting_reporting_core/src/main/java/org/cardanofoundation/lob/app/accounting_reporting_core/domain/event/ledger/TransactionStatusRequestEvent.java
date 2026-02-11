package org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger;

import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@ToString
public class TransactionStatusRequestEvent {

    Map<String, List<String>> organisationTransactionIdMap;

}
