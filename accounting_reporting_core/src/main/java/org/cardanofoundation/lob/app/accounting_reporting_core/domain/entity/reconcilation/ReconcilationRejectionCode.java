package org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation;

public enum ReconcilationRejectionCode {

    SOURCE_RECONCILATION_FAIL, // transaction version will have to be ignored because it cannot be extracted again
    SINK_RECONCILATION_FAIL, // blockchain is missing a transaction or it is not finalised yet
    SINK_RECONCILATION_MISMATCH,

    TX_NOT_IN_ERP, // transaction is not in ERP, typically this is a technical error
    TX_NOT_IN_LOB, // transaction is not in LOB, typically it has not been imported yet at all

}
