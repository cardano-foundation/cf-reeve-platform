package org.cardanofoundation.lob.app.accounting_reporting_core.domain.core;

import static org.cardanofoundation.lob.app.support.crypto.SHA3.digestAsHex;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.*;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.annotations.LOBVersionSourceRelevant;

@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
@Getter
@ToString
@EqualsAndHashCode
public class Transaction {

    @NotBlank
    @LOBVersionSourceRelevant
    private String id;

    @LOBVersionSourceRelevant
    @Getter
    @Size(min = 1, max =  255) @NotBlank
    private String internalTransactionNumber;

    @NotBlank
    private String batchId;

    @NotNull
    @LOBVersionSourceRelevant
    private LocalDate entryDate;

    @NotNull
    @LOBVersionSourceRelevant
    private TransactionType transactionType;

    @NotNull
    private Organisation organisation;

    @NotNull
    @Builder.Default
    private LedgerDispatchStatus ledgerDispatchStatus = LedgerDispatchStatus.NOT_DISPATCHED;

    @NotNull
    @Builder.Default
    private TxValidationStatus txValidationStatus = TxValidationStatus.VALIDATED;

    @Builder.Default
    private boolean transactionApproved = false;

    @Builder.Default
    private boolean ledgerDispatchApproved = false;

    @NotNull
    private YearMonth accountingPeriod;

    @Builder.Default
    @NotEmpty
    private Set<TransactionItem> items = new LinkedHashSet<>();

    @Builder.Default
    private Set<Violation> violations = new LinkedHashSet<>();

    public static String id(String organisationId,
                            String internalTransactionNumber) {
        return digestAsHex("%s::%s".formatted(
                organisationId,
                internalTransactionNumber
        ));
    }

}
