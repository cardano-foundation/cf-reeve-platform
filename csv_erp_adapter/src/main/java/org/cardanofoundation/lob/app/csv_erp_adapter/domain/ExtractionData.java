package org.cardanofoundation.lob.app.csv_erp_adapter.domain;


import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.UserExtractionParameters;
import org.cardanofoundation.lob.app.organisation.domain.SystemExtractionParameters;

public record ExtractionData(String batchId, String organisationId, String user, UserExtractionParameters userExtractionParameters, SystemExtractionParameters systemExtractionParameters, byte[] file) {
}
