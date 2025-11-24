package org.cardanofoundation.lob.app.accounting_reporting_core.resource.views;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonFormat;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.ReconcilationCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionProcessingStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationRejectionCodeRequest;


@Getter
@Setter
@AllArgsConstructor
public class TransactionReconciliationTransactionsView {

    private String id;
    private String internalTransactionNumber;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate entryDate;

    private TransactionType transactionType;

    private DataSourceView dataSource;

    private Optional<TransactionStatus> status = Optional.empty();

    private Optional<TransactionProcessingStatus> statistic = Optional.of(TransactionProcessingStatus.PENDING);

    private Optional<TxValidationStatus> validationStatus = Optional.of(TxValidationStatus.VALIDATED);

    private boolean transactionApproved = false;

    private boolean ledgerDispatchApproved = false;

    private BigDecimal amountTotalLcy;

    private boolean itemRejection = false;

    private ReconciliationCodeView reconciliationSource = ReconciliationCodeView.NEVER;

    private ReconciliationCodeView reconciliationSink = ReconciliationCodeView.NEVER;

    private ReconciliationCodeView reconciliationFinalStatus = ReconciliationCodeView.NEVER;

    private Set<ReconciliationRejectionCodeRequest> reconciliationRejectionCode = new LinkedHashSet<>();

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime reconciliationDate;

    private Set<TransactionItemView> items = new LinkedHashSet<>();

    private Set<ViolationView> violations = new LinkedHashSet<>();

    public enum ReconciliationCodeView {
        OK,
        NOK,
        NEVER;

        public static ReconciliationCodeView of(ReconcilationCode code) {
            return switch (code) {
                case OK -> ReconciliationCodeView.OK;
                case NOK -> ReconciliationCodeView.NOK;
            };
        }
    }


}
