package org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity;

public enum TransactionProcessingStatus {

    APPROVE, /** Ready to approve */

    PENDING, /** when exist a violation or a rejection related to LOB problem */

    INVALID, /** when exist a violation or a rejection related to ERP problem*/

    PUBLISH, /** Ready to published */

    PUBLISHED, /** Sent to the published */

    DISPATCHED ; /** DISPATCHED, COMPLETED or FINALIZED status */

}
