package org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationRejectionCode;

class ReconciliationRejectionCodeRequestTest {

    // ============== of() mapping tests ==============

    static Stream<Arguments> ofMappings() {
        return Stream.of(
                Arguments.of(ReconcilationRejectionCode.SOURCE_RECONCILATION_FAIL,    true,  ReconciliationRejectionCodeRequest.NEW_VERSION_NOT_PUBLISHED),
                Arguments.of(ReconcilationRejectionCode.SOURCE_RECONCILATION_FAIL,    false, ReconciliationRejectionCodeRequest.NEW_VERSION_NOT_PUBLISHED),
                Arguments.of(ReconcilationRejectionCode.SOURCE_RECONCILATION_MISMATCH, true, ReconciliationRejectionCodeRequest.NEW_VERSION),
                Arguments.of(ReconcilationRejectionCode.SOURCE_RECONCILATION_MISMATCH, false, ReconciliationRejectionCodeRequest.NEW_VERSION),
                Arguments.of(ReconcilationRejectionCode.SINK_RECONCILATION_FAIL,      true,  ReconciliationRejectionCodeRequest.IN_PROCESSING),
                Arguments.of(ReconcilationRejectionCode.SINK_RECONCILATION_FAIL,      false, ReconciliationRejectionCodeRequest.IN_PROCESSING),
                Arguments.of(ReconcilationRejectionCode.SINK_RECONCILATION_MISMATCH,  true,  ReconciliationRejectionCodeRequest.NEW_VERSION_PUBLISHED),
                Arguments.of(ReconcilationRejectionCode.TX_NOT_IN_ERP,                true,  ReconciliationRejectionCodeRequest.MISSING_IN_ERP),
                Arguments.of(ReconcilationRejectionCode.TX_NOT_IN_LOB,                false, ReconciliationRejectionCodeRequest.NEW_IN_ERP)
        );
    }

    @ParameterizedTest(name = "{0} + ledgerApproval={1} -> {2}")
    @MethodSource("ofMappings")
    void of_shouldMapCorrectly(ReconcilationRejectionCode code, boolean ledgerApproval,
                               ReconciliationRejectionCodeRequest expected) {
        assertThat(ReconciliationRejectionCodeRequest.of(code, ledgerApproval)).isEqualTo(expected);
    }

    @Test
    void of_sourceReconcilationFail_alwaysMapsToNewVersionNotPublished_regardlessOfApproval() {
        // The old behavior branched on ledgerDispatchApproval; new behavior ignores it.
        assertThat(ReconciliationRejectionCodeRequest.of(ReconcilationRejectionCode.SOURCE_RECONCILATION_FAIL, true))
                .isEqualTo(ReconciliationRejectionCodeRequest.NEW_VERSION_NOT_PUBLISHED);
        assertThat(ReconciliationRejectionCodeRequest.of(ReconcilationRejectionCode.SOURCE_RECONCILATION_FAIL, false))
                .isEqualTo(ReconciliationRejectionCodeRequest.NEW_VERSION_NOT_PUBLISHED);
    }

    @Test
    void of_sourceReconcilationMismatch_mapsToNewVersion() {
        assertThat(ReconciliationRejectionCodeRequest.of(ReconcilationRejectionCode.SOURCE_RECONCILATION_MISMATCH, true))
                .isEqualTo(ReconciliationRejectionCodeRequest.NEW_VERSION);
        assertThat(ReconciliationRejectionCodeRequest.of(ReconcilationRejectionCode.SOURCE_RECONCILATION_MISMATCH, false))
                .isEqualTo(ReconciliationRejectionCodeRequest.NEW_VERSION);
    }

    // ============== toReconcilationRejectionCode() mapping tests ==============

    static Stream<Arguments> toCodeMappings() {
        return Stream.of(
                Arguments.of(ReconciliationRejectionCodeRequest.MISSING_IN_ERP,          ReconcilationRejectionCode.TX_NOT_IN_ERP),
                Arguments.of(ReconciliationRejectionCodeRequest.IN_PROCESSING,            ReconcilationRejectionCode.SINK_RECONCILATION_FAIL),
                Arguments.of(ReconciliationRejectionCodeRequest.NEW_IN_ERP,              ReconcilationRejectionCode.TX_NOT_IN_LOB),
                Arguments.of(ReconciliationRejectionCodeRequest.NEW_VERSION_NOT_PUBLISHED, ReconcilationRejectionCode.SOURCE_RECONCILATION_FAIL),
                Arguments.of(ReconciliationRejectionCodeRequest.NEW_VERSION,             ReconcilationRejectionCode.SOURCE_RECONCILATION_MISMATCH),
                Arguments.of(ReconciliationRejectionCodeRequest.NEW_VERSION_PUBLISHED,   ReconcilationRejectionCode.SINK_RECONCILATION_MISMATCH)
        );
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("toCodeMappings")
    void toReconcilationRejectionCode_shouldMapCorrectly(ReconciliationRejectionCodeRequest request,
                                                         ReconcilationRejectionCode expected) {
        assertThat(ReconciliationRejectionCodeRequest.toReconcilationRejectionCode(request)).isEqualTo(expected);
    }

    @Test
    void toReconcilationRejectionCode_newVersionMapsToSourceMismatch() {
        assertThat(ReconciliationRejectionCodeRequest.toReconcilationRejectionCode(ReconciliationRejectionCodeRequest.NEW_VERSION))
                .isEqualTo(ReconcilationRejectionCode.SOURCE_RECONCILATION_MISMATCH);
    }

    @Test
    void toReconcilationRejectionCode_newVersionNotPublishedMapsToSourceFail() {
        assertThat(ReconciliationRejectionCodeRequest.toReconcilationRejectionCode(ReconciliationRejectionCodeRequest.NEW_VERSION_NOT_PUBLISHED))
                .isEqualTo(ReconcilationRejectionCode.SOURCE_RECONCILATION_FAIL);
    }

    @Test
    void roundTrip_sourceReconcilationFail_throughNewVersionNotPublished() {
        ReconciliationRejectionCodeRequest req = ReconciliationRejectionCodeRequest.of(ReconcilationRejectionCode.SOURCE_RECONCILATION_FAIL, false);
        assertThat(ReconciliationRejectionCodeRequest.toReconcilationRejectionCode(req))
                .isEqualTo(ReconcilationRejectionCode.SOURCE_RECONCILATION_FAIL);
    }

    @Test
    void roundTrip_sourceReconcilationMismatch_throughNewVersion() {
        ReconciliationRejectionCodeRequest req = ReconciliationRejectionCodeRequest.of(ReconcilationRejectionCode.SOURCE_RECONCILATION_MISMATCH, true);
        assertThat(ReconciliationRejectionCodeRequest.toReconcilationRejectionCode(req))
                .isEqualTo(ReconcilationRejectionCode.SOURCE_RECONCILATION_MISMATCH);
    }
}
