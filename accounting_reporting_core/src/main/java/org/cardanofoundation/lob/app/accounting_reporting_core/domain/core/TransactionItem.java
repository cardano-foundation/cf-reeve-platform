package org.cardanofoundation.lob.app.accounting_reporting_core.domain.core;

import static org.cardanofoundation.lob.app.support.crypto.SHA3.digestAsHex;

import java.math.BigDecimal;
import java.util.Optional;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import lombok.*;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.annotations.LOBVersionSourceRelevant;

@Builder(toBuilder = true)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@EqualsAndHashCode
public class TransactionItem {

    @LOBVersionSourceRelevant
    @NotBlank private String id;

    @LOBVersionSourceRelevant
    @NotNull private BigDecimal amountFcy;

    @LOBVersionSourceRelevant
    @NotNull private BigDecimal amountLcy;

    @Builder.Default
    private Optional<Account> accountDebit = Optional.empty();

    @Builder.Default
    private Optional<Account> accountCredit = Optional.empty();

    @Builder.Default
    private Optional<AccountEvent> accountEvent = Optional.empty();

    @Builder.Default
    private Optional<Project> project = Optional.empty();

    @Builder.Default
    private Optional<CostCenter> costCenter = Optional.empty();

    @Builder.Default
    private Optional<Document> document = Optional.empty(); // initially we allow empty but later as part of business rules we check if document is present

    @NotNull
    @LOBVersionSourceRelevant
    private OperationType operationType;

    @NotNull
    @PositiveOrZero
    @LOBVersionSourceRelevant
    private BigDecimal fxRate;

    public static String id(String transactionId,
                            String lineNo) {
        return digestAsHex("%s::%s".formatted(
                transactionId, lineNo
        ));
    }

}
