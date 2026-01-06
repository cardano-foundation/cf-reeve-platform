package org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@ToString
public class TxRollbackEvent {

    private String transactionId;

}
