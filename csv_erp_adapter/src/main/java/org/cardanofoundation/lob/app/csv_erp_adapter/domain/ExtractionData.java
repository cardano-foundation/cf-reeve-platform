package org.cardanofoundation.lob.app.csv_erp_adapter.domain;


import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.SystemExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.UserExtractionParameters;

public record ExtractionData(String batchId, String organisationId, String user, UserExtractionParameters userExtractionParameters, SystemExtractionParameters systemExtractionParameters, byte[] file) {
}
